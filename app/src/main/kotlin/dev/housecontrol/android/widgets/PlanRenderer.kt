package dev.housecontrol.android.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import dev.housecontrol.android.core.ColorMath
import dev.housecontrol.android.core.HomeSnapshot
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The home from above for the Home widget: rooms, furniture, the air conditioners on their
 * walls and every lamp that is on glowing in its own colour, as the 3D view lights the rooms.
 */
object PlanRenderer {
    fun render(snapshot: HomeSnapshot, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rooms = snapshot.rooms
        if (rooms.isEmpty()) return bitmap
        val minX = rooms.minOf { it.optDouble("x") }
        val minZ = rooms.minOf { it.optDouble("z") }
        val maxX = rooms.maxOf { it.optDouble("x") + it.optDouble("w") }
        val maxZ = rooms.maxOf { it.optDouble("z") + it.optDouble("d") }
        val pad = min(width, height) * 0.06f
        val scale = min((width - 2 * pad) / (maxX - minX), (height - 2 * pad) / (maxZ - minZ)).toFloat()
        val offsetX = (width - (maxX - minX).toFloat() * scale) / 2
        val offsetY = (height - (maxZ - minZ).toFloat() * scale) / 2
        fun px(x: Double) = offsetX + (x - minX).toFloat() * scale
        fun py(z: Double) = offsetY + (z - minZ).toFloat() * scale

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1.5f, scale * 0.05f) }
        val corner = scale * 0.08f

        // Floors, then the light on them, then walls and fixtures on top.
        for (room in rooms) {
            fill.color = floorColor(room.optString("floor"))
            canvas.drawRoundRect(roomRect(room, ::px, ::py), corner, corner, fill)
        }
        for (light in snapshot.lights.filter { it.optBoolean("on") }) {
            val color = lampColor(light)
            val brightness = light.optDouble("brightness", 1.0).coerceIn(0.05, 1.0)
            val room = rooms.firstOrNull { it.optString("id") == light.optString("room") }
            val reach = (room?.let { min(it.optDouble("w"), it.optDouble("d")) } ?: 3.0).toFloat() * scale * 0.62f
            val cx = px(light.optDouble("x"))
            val cy = py(light.optDouble("z"))
            val alpha = (90 + 150 * brightness).toInt()
            fill.shader = RadialGradient(cx, cy, max(1f, reach), withAlpha(color, alpha), withAlpha(color, 0), Shader.TileMode.CLAMP)
            canvas.save()
            room?.let { canvas.clipRect(roomRect(it, ::px, ::py)) }
            canvas.drawCircle(cx, cy, reach, fill)
            canvas.restore()
            fill.shader = null
        }
        fill.color = Color.argb(30, 255, 255, 255)
        for (item in snapshot.furniture) {
            val w = item.optDouble("w").toFloat() * scale
            val d = item.optDouble("d").toFloat() * scale
            canvas.save()
            canvas.translate(px(item.optDouble("x")), py(item.optDouble("z")))
            canvas.rotate(item.optDouble("rot").toFloat())
            canvas.drawRoundRect(RectF(-w / 2, -d / 2, w / 2, d / 2), scale * 0.06f, scale * 0.06f, fill)
            canvas.restore()
        }
        stroke.color = Color.argb(70, 220, 230, 250)
        for (room in rooms) canvas.drawRoundRect(roomRect(room, ::px, ::py), corner, corner, stroke)

        // Air conditioners: a bar along their wall, blue while they run.
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = max(3f, scale * 0.14f) }
        for (ac in snapshot.acs) {
            bar.color = if (ac.optBoolean("on")) Color.rgb(90, 167, 255) else Color.argb(120, 200, 210, 230)
            val x = px(ac.optDouble("x"))
            val y = py(ac.optDouble("z"))
            val half = scale * 0.4f
            when (ac.optString("wall")) {
                "east", "west" -> canvas.drawLine(x, y - half, x, y + half, bar)
                else -> canvas.drawLine(x - half, y, x + half, y, bar)
            }
        }
        // Lamps: a bright dot when on, a faint ring when off.
        for (light in snapshot.lights) {
            val x = px(light.optDouble("x"))
            val y = py(light.optDouble("z"))
            val r = max(2.5f, scale * 0.09f)
            if (light.optBoolean("on")) {
                fill.color = blend(lampColor(light), Color.WHITE, 0.55f)
                canvas.drawCircle(x, y, r, fill)
            } else {
                stroke.color = Color.argb(110, 200, 210, 230)
                canvas.drawCircle(x, y, r * 0.8f, stroke)
            }
        }
        return bitmap
    }

    private fun roomRect(room: JSONObject, px: (Double) -> Float, py: (Double) -> Float) = RectF(
        px(room.optDouble("x")), py(room.optDouble("z")),
        px(room.optDouble("x") + room.optDouble("w")), py(room.optDouble("z") + room.optDouble("d")),
    )

    private fun floorColor(kind: String) = when (kind) {
        "walnut" -> Color.rgb(46, 36, 32)
        "tile" -> Color.rgb(38, 44, 56)
        "stone" -> Color.rgb(44, 46, 52)
        else -> Color.rgb(52, 44, 36) // oak
    }

    /** A lamp's colour: its own when it shows one, else its white temperature. */
    fun lampColor(light: JSONObject): Int {
        light.optString("color").takeIf { it.startsWith("#") }?.let(ColorMath::rgb)?.let { (r, g, b) ->
            return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        }
        return kelvin(light.optDouble("temp", 3000.0))
    }

    /** Tanner Helland's approximation of a black body's colour. */
    fun kelvin(k: Double): Int {
        val t = k.coerceIn(1000.0, 40000.0) / 100
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = if (t >= 66) 255.0 else if (t <= 19) 0.0 else 138.5177312231 * ln(t - 10) - 305.0447927307
        return Color.rgb(r.coerceIn(0.0, 255.0).toInt(), g.coerceIn(0.0, 255.0).toInt(), b.coerceIn(0.0, 255.0).toInt())
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
