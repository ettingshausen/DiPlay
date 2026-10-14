package com.shilapi.xcertplay.media

import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayTouchMapperTest {
    private val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)

    @Test fun contentCornersMapToCanvasCorners() {
        contact(MotionEvent.ACTION_DOWN, content.left, content.top).let {
            assertEquals(0.0, it.x, 1e-6)
            assertEquals(0.0, it.y, 1e-6)
            assertTrue(it.down)
        }
        contact(MotionEvent.ACTION_UP, content.left + content.width, content.top + content.height).let {
            assertEquals(1.0, it.x, 1e-6)
            assertEquals(1.0, it.y, 1e-6)
            assertFalse(it.down)
        }
    }

    @Test fun centreRemainsCentred() {
        contact(MotionEvent.ACTION_MOVE, content.left + content.width / 2, content.top + content.height / 2).let {
            assertEquals(0.5, it.x, 1e-6)
            assertEquals(0.5, it.y, 1e-6)
        }
    }

    @Test fun dragIntoABarClampsToTheCanvasEdge() {
        assertEquals(0.0, contact(MotionEvent.ACTION_MOVE, content.left, content.top + content.height / 2).x, 1e-6)
        assertEquals(1.0, contact(MotionEvent.ACTION_MOVE, content.left + content.width, content.top + content.height / 2).x, 1e-6)
    }

    @Test fun cancelInABarReleasesTheContact() {
        assertFalse(contact(MotionEvent.ACTION_CANCEL, content.left, content.top + content.height / 2).down)
    }

    @Test fun fullViewOverloadKeepsExistingCoordinates() {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 480f, 247.5f, 0)
        try {
            val contact = CarPlayTouchMapper.contacts(event, 1920, 990).first()
            assertEquals(0.25, contact.x, 1e-6)
            assertEquals(0.25, contact.y, 1e-6)
            assertTrue(contact.down)
        } finally {
            event.recycle()
        }
    }

    @Test fun alwaysEmitsTwoSlotsAndClosesTheGesture() {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 480f, 247.5f, 0)
        val up = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 480f, 247.5f, 0)
        try {
            val atDown = CarPlayTouchMapper.contacts(down, content)
            assertEquals(2, atDown.size)
            assertTrue(atDown[0].down)
            assertFalse(atDown[1].down)
            val atUp = CarPlayTouchMapper.contacts(up, content)
            assertFalse(atUp[0].down)
            assertFalse(atUp[1].down)
        } finally {
            down.recycle(); up.recycle()
        }
    }

    @Test fun reorderedIndexesKeepStableSlots() {
        // DiLink 4.0's touch controller reports the second finger at index 0; slots must
        // follow pointer IDs, so the two fingers never swap between HID slots.
        val down = motionEvent(MotionEvent.ACTION_DOWN, ids = intArrayOf(0),
            xs = floatArrayOf(content.left), ys = floatArrayOf(content.top + 500f))
        val secondDown = motionEvent(
            MotionEvent.ACTION_POINTER_DOWN or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            ids = intArrayOf(5, 0),
            xs = floatArrayOf(content.left + 700f, content.left),
            ys = floatArrayOf(content.top + 520f, content.top + 500f),
        )
        val move = motionEvent(MotionEvent.ACTION_MOVE, ids = intArrayOf(5, 0),
            xs = floatArrayOf(content.left + 750f, content.left + 150f),
            ys = floatArrayOf(content.top + 560f, content.top + 520f))
        val up = motionEvent(MotionEvent.ACTION_UP, ids = intArrayOf(5),
            xs = floatArrayOf(content.left + 750f), ys = floatArrayOf(content.top + 560f))
        try {
            CarPlayTouchMapper.contacts(down, content)
            val both = CarPlayTouchMapper.contacts(secondDown, content)
            assertEquals(0.0, both[0].x, 1e-6)      // finger 0 (id 0) keeps slot 0
            assertTrue(both[0].down)
            assertEquals((700f / content.width).toDouble(), both[1].x, 1e-6) // finger 5 lands in slot 1
            assertTrue(both[1].down)

            val moved = CarPlayTouchMapper.contacts(move, content)
            assertEquals((150f / content.width).toDouble(), moved[0].x, 1e-6)   // finger 0 still slot 0 after the reorder
            assertEquals((750f / content.width).toDouble(), moved[1].x, 1e-6)   // finger 5 still slot 1
            assertTrue(moved[0].down && moved[1].down)

            val lifted = CarPlayTouchMapper.contacts(up, content)
            assertFalse(lifted[0].down)
            assertFalse(lifted[1].down)
        } finally {
            down.recycle(); secondDown.recycle(); move.recycle(); up.recycle()
        }
    }

    @Test fun liftedFingerKeepsItsSlotWhileTheOtherMoves() {
        val down = motionEvent(MotionEvent.ACTION_DOWN, ids = intArrayOf(0),
            xs = floatArrayOf(content.left), ys = floatArrayOf(content.top + 500f))
        val both = motionEvent(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            ids = intArrayOf(0, 5),
            xs = floatArrayOf(content.left, content.left + 700f),
            ys = floatArrayOf(content.top + 500f, content.top + 520f))
        val lift = motionEvent(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            ids = intArrayOf(0, 5),
            xs = floatArrayOf(content.left + 10f, content.left + 700f),
            ys = floatArrayOf(content.top + 505f, content.top + 520f))
        val move = motionEvent(MotionEvent.ACTION_MOVE, ids = intArrayOf(0),
            xs = floatArrayOf(content.left + 200f), ys = floatArrayOf(content.top + 510f))
        try {
            CarPlayTouchMapper.contacts(down, content)
            CarPlayTouchMapper.contacts(both, content)
            val afterLift = CarPlayTouchMapper.contacts(lift, content)
            assertTrue(afterLift[0].down)          // finger 0 keeps moving
            assertFalse(afterLift[1].down)         // finger 5 lifted
            assertEquals((700f / content.width).toDouble(), afterLift[1].x, 1e-6)  // last position retained
            val afterMove = CarPlayTouchMapper.contacts(move, content)
            assertEquals((200f / content.width).toDouble(), afterMove[0].x, 1e-6)
            assertEquals((700f / content.width).toDouble(), afterMove[1].x, 1e-6)  // slot 1 still finger 5's slot
            assertTrue(afterMove[0].down)
            assertFalse(afterMove[1].down)
        } finally {
            down.recycle(); both.recycle(); lift.recycle(); move.recycle()
        }
    }

    @Test fun aThirdFingerDoesNotDisplaceAssignedSlots() {
        val down = motionEvent(MotionEvent.ACTION_DOWN, ids = intArrayOf(0),
            xs = floatArrayOf(content.left), ys = floatArrayOf(content.top + 500f))
        val second = motionEvent(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            ids = intArrayOf(0, 5),
            xs = floatArrayOf(content.left, content.left + 700f),
            ys = floatArrayOf(content.top + 500f, content.top + 520f))
        val third = motionEvent(MotionEvent.ACTION_MOVE,
            ids = intArrayOf(0, 5, 9),
            xs = floatArrayOf(content.left + 120f, content.left + 700f, content.left + 900f),
            ys = floatArrayOf(content.top + 500f, content.top + 520f, content.top + 300f))
        try {
            CarPlayTouchMapper.contacts(down, content)
            CarPlayTouchMapper.contacts(second, content)
            val moved = CarPlayTouchMapper.contacts(third, content)
            assertEquals((120f / content.width).toDouble(), moved[0].x, 1e-6)
            assertEquals((700f / content.width).toDouble(), moved[1].x, 1e-6)
        } finally {
            down.recycle(); second.recycle(); third.recycle()
        }
    }

    private fun motionEvent(action: Int, ids: IntArray, xs: FloatArray, ys: FloatArray): MotionEvent {
        val coords = Array(ids.size) { PointerCoords().apply { clear(); x = xs[it]; y = ys[it]; pressure = 1f } }
        return MotionEvent.obtain(0L, 0L, action, ids.size, ids, coords, 0, 0f, 0f, 0, 0, 0, 0)
    }

    private fun contact(action: Int, x: Float, y: Float) = MotionEvent.obtain(0, 0, action, x, y, 0).let {
        try {
            val contacts = CarPlayTouchMapper.contacts(it, content)
            contacts.first { c -> c.down || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL }
        } finally {
            it.recycle()
        }
    }
}
