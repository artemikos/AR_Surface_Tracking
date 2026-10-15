package com.example.arlab

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    enum class Shape { CUBE, SPHERE, CYLINDER }

    // Один размещённый объект: якорь + узел + два внешних вида + данные для движения по кругу
    class PlacedObject(
        val anchorNode: AnchorNode,
        val node: TransformableNode,
        val normal: ModelRenderable,
        val highlighted: ModelRenderable,
        val baseRadius: Float            // радиус "зоны пересечения" при масштабе 1
    ) {
        var angle = 0f                   // текущий угол на окружности
        var center = Vector3.zero()      // центр окружности (в координатах якоря)
        var lastValidPos = Vector3.zero()  // последняя позиция без пересечений
        var lastValidScale = Vector3.one() // последний масштаб без пересечений
        var recenter = false             // после перетаскивания пересчитать центр окружности
    }

    companion object {
        const val SIZE = 0.1f
        const val ORBIT_RADIUS = 0.10f   // радиус окружности, м
        const val ORBIT_SPEED = 0.25f    // скорость, рад/с (полный круг ~52 с)
    }

    private lateinit var arFragment: ArFragment

    private var currentShape = Shape.CUBE
    private val objects = mutableListOf<PlacedObject>()
    private var selected: PlacedObject? = null
    private var updateRegistered = false

    // Готовые внешние виды для каждой фигуры: first = обычный, second = выделенный
    private val renderables = HashMap<Shape, Pair<ModelRenderable, ModelRenderable>>()

    private val colorNormal = Color(0.2f, 0.5f, 1f)
    private val colorSelected = Color(1f, 0.55f, 0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        prepareRenderables()

        findViewById<Button>(R.id.btnCube).setOnClickListener { currentShape = Shape.CUBE }
        findViewById<Button>(R.id.btnSphere).setOnClickListener { currentShape = Shape.SPHERE }
        findViewById<Button>(R.id.btnCylinder).setOnClickListener { currentShape = Shape.CYLINDER }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { deleteSelected() }

        // Тап по распознанной плоскости -> ставим объект через Anchor
        arFragment.setOnTapArPlaneListener { hitResult, _, _ ->
            val pair = renderables[currentShape] ?: return@setOnTapArPlaneListener

            // Покадровое обновление (движение по кругу + запрет пересечений) подключаем один раз
            if (!updateRegistered) {
                arFragment.arSceneView.scene.addOnUpdateListener { frameTime ->
                    update(frameTime.deltaSeconds)
                }
                updateRegistered = true
            }

            // Запрет пересечения при установке
            val pose = hitResult.hitPose
            val newPos = Vector3(pose.tx(), pose.ty(), pose.tz())
            val newRadius = radiusOf(currentShape)
            for (o in objects) {
                val d = Vector3.subtract(o.node.worldPosition, newPos).length()
                if (d < newRadius + o.baseRadius * o.node.worldScale.x) {
                    Toast.makeText(this, "Здесь уже есть объект", Toast.LENGTH_SHORT).show()
                    return@setOnTapArPlaneListener
                }
            }

            val anchor = hitResult.createAnchor()
            val anchorNode = AnchorNode(anchor)
            anchorNode.setParent(arFragment.arSceneView.scene)

            val node = TransformableNode(arFragment.transformationSystem)
            node.scaleController.minScale = 0.5f
            node.scaleController.maxScale = 3f
            node.renderable = pair.first
            node.setParent(anchorNode)

            val placed = PlacedObject(anchorNode, node, pair.first, pair.second, newRadius)
            // Объект стартует в точке тапа (угол 0), центр окружности смещён на радиус влево
            placed.center = Vector3(-ORBIT_RADIUS, 0f, 0f)
            placed.lastValidPos = node.localPosition
            placed.lastValidScale = node.localScale
            objects.add(placed)

            // Тап по объекту -> выбираем его
            node.setOnTapListener { _, _ -> select(placed) }

            select(placed)
        }
    }

    private fun radiusOf(shape: Shape): Float = when (shape) {
        Shape.CUBE -> SIZE * 0.71f      // половина диагонали куба
        Shape.SPHERE -> SIZE / 2
        Shape.CYLINDER -> SIZE / 2
    }

    // Пересекается ли объект с каким-нибудь другим (по кругам-зонам с учётом масштаба)
    private fun collides(o: PlacedObject): Boolean {
        val p = o.node.worldPosition
        val r = o.baseRadius * o.node.worldScale.x
        for (other in objects) {
            if (other === o) continue
            val d = Vector3.subtract(other.node.worldPosition, p).length()
            if (d < r + other.baseRadius * other.node.worldScale.x) return true
        }
        return false
    }

    // Вызывается каждый кадр
    private fun update(dt: Float) {
        for (o in objects) {
            // Пользователь двигает / масштабирует объект: при пересечении откатываем
            if (o.node.isTransforming) {
                o.recenter = true
                if (collides(o)) {
                    o.node.localPosition = o.lastValidPos
                    o.node.localScale = o.lastValidScale
                } else {
                    o.lastValidPos = o.node.localPosition
                    o.lastValidScale = o.node.localScale
                }
                continue
            }

            // После перетаскивания окружность строится от новой позиции
            if (o.recenter) {
                val p = o.node.localPosition
                o.center = Vector3(
                    p.x - ORBIT_RADIUS * cos(o.angle),
                    p.y,
                    p.z - ORBIT_RADIUS * sin(o.angle)
                )
                o.lastValidPos = p
                o.recenter = false
            }

            // Медленное движение по окружности; если путь занят, этот кадр пропускаем
            val a = o.angle + ORBIT_SPEED * dt
            val old = o.node.localPosition
            o.node.localPosition = Vector3(
                o.center.x + ORBIT_RADIUS * cos(a),
                o.center.y,
                o.center.z + ORBIT_RADIUS * sin(a)
            )
            if (collides(o)) {
                o.node.localPosition = old
            } else {
                o.angle = a
                o.lastValidPos = o.node.localPosition
            }
        }
    }

    // Создаём фигуры и два материала (обычный и выделенный) без внешних моделей
    private fun prepareRenderables() {
        MaterialFactory.makeOpaqueWithColor(this, colorNormal).thenAccept { normalMat ->
            MaterialFactory.makeOpaqueWithColor(this, colorSelected).thenAccept { selMat ->
                val offset = Vector3(0f, SIZE / 2, 0f) // чтобы фигура стояла на плоскости

                renderables[Shape.CUBE] = Pair(
                    ShapeFactory.makeCube(Vector3(SIZE, SIZE, SIZE), offset, normalMat),
                    ShapeFactory.makeCube(Vector3(SIZE, SIZE, SIZE), offset, selMat)
                )
                renderables[Shape.SPHERE] = Pair(
                    ShapeFactory.makeSphere(SIZE / 2, offset, normalMat),
                    ShapeFactory.makeSphere(SIZE / 2, offset, selMat)
                )
                renderables[Shape.CYLINDER] = Pair(
                    ShapeFactory.makeCylinder(SIZE / 2, SIZE, offset, normalMat),
                    ShapeFactory.makeCylinder(SIZE / 2, SIZE, offset, selMat)
                )
            }
        }
    }

    // Выбор объекта: меняем внешний вид
    private fun select(obj: PlacedObject?) {
        selected = obj
        for (o in objects) {
            o.node.renderable = if (o == obj) o.highlighted else o.normal
        }
    }

    // Удаление выбранного объекта
    private fun deleteSelected() {
        val obj = selected
        if (obj == null) {
            Toast.makeText(this, "Сначала выберите объект тапом", Toast.LENGTH_SHORT).show()
            return
        }
        obj.node.setParent(null)
        obj.anchorNode.anchor?.detach()
        obj.anchorNode.setParent(null)
        objects.remove(obj)
        selected = null
    }
}
