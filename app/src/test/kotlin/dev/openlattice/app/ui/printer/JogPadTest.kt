package dev.openlattice.app.ui.printer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JogPadTest {
    private val w = 200f // radius 100, centre (100, 100)

    @Test
    fun `centre homes`() = assertEquals(JogTarget.Home, hit(Offset(100f, 100f), w))

    @Test
    fun `inner ring moves 1 mm, outer ring 10 mm`() {
        assertEquals(JogTarget.Move('y', 1.0), hit(Offset(100f, 60f), w)) // up, inner
        assertEquals(JogTarget.Move('y', 10.0), hit(Offset(100f, 10f), w)) // up, outer
        assertEquals(JogTarget.Move('y', -1.0), hit(Offset(100f, 140f), w)) // down
        assertEquals(JogTarget.Move('x', 10.0), hit(Offset(195f, 100f), w)) // right, outer
        assertEquals(JogTarget.Move('x', -1.0), hit(Offset(60f, 100f), w)) // left
    }

    @Test
    fun `outside the circle does nothing`() = assertNull(hit(Offset(2f, 2f), w))
}
