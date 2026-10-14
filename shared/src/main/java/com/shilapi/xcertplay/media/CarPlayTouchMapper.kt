package com.shilapi.xcertplay.media

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact

/**
 * Converts Android MotionEvents into normalized CarPlay touch contacts.
 *
 * Slots are assigned from stable pointer IDs, not from per-event pointer indexes. Some
 * head-unit touch controllers reorder pointer indexes during a multi-finger gesture —
 * observed on DiLink 4.0 as `ACTION_POINTER_DOWN(0)` when the second finger lands, which
 * moves the second finger into index 0. An index-based slot would then swap the two
 * fingers between HID slots mid-gesture, and the iPhone's pinch recognition never starts.
 * A slot stays reserved for its pointer until the gesture ends, so a lifted finger keeps
 * its last position while the remaining finger moves, and a pointer that disappears from
 * a frame without `ACTION_POINTER_UP` is treated as lifted.
 */
object CarPlayTouchMapper {
    private const val MAX_CONTACTS = 2

    private val slotByPointerId = HashMap<Int, Int>()
    private val lastContactByPointerId = HashMap<Int, AirPlayContact>()

    fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<AirPlayContact> =
        contacts(event, CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()))

    fun contacts(event: MotionEvent, content: CarPlayVideoLayout): List<AirPlayContact> {
        val width = content.width.coerceAtLeast(1f)
        val height = content.height.coerceAtLeast(1f)
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            // ACTION_DOWN always begins a fresh gesture: some interception paths swallow the
            // previous gesture's ACTION_UP, so stale assignments must not survive into it.
            slotByPointerId.clear()
            lastContactByPointerId.clear()
        }

        // Track new pointers, oldest first, into free slots. A frame without a preceding
        // ACTION_DOWN (an isolated MOVE in some tests and driver edge cases) assigns slots
        // on the fly, matching the old stateless behavior.
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || slotByPointerId.isEmpty()) {
            for (index in 0 until event.pointerCount) {
                val id = event.getPointerId(index)
                if (slotByPointerId.containsKey(id) || slotByPointerId.size >= MAX_CONTACTS) continue
                val used = slotByPointerId.values.toSet()
                slotByPointerId[id] = (0 until MAX_CONTACTS).first { it !in used }
            }
        }

        val liftedId = if (action == MotionEvent.ACTION_POINTER_UP) {
            event.getPointerId(event.actionIndex)
        } else -1

        // Refresh remembered contacts from this event, including the final UP/CANCEL frame.
        for (index in 0 until event.pointerCount) {
            val id = event.getPointerId(index)
            if (!slotByPointerId.containsKey(id)) continue
            lastContactByPointerId[id] = AirPlayContact(
                id = id,
                x = ((event.getX(index) - content.left).toDouble() / width).coerceIn(0.0, 1.0),
                y = ((event.getY(index) - content.top).toDouble() / height).coerceIn(0.0, 1.0),
                down = true,
            )
        }
        val present = (0 until event.pointerCount).map { event.getPointerId(it) }.toSet()
        for ((id, contact) in lastContactByPointerId) {
            if (id !in present || id == liftedId || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lastContactByPointerId[id] = contact.copy(down = false)
            }
        }

        val final = slotContacts(content)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            slotByPointerId.clear()
            lastContactByPointerId.clear()
        }
        return final
    }

    /** Emits exactly [MAX_CONTACTS] contacts ordered by slot; empty slots report an inactive finger. */
    private fun slotContacts(content: CarPlayVideoLayout): List<AirPlayContact> =
        (0 until MAX_CONTACTS).map { slot ->
            val id = slotByPointerId.entries.firstOrNull { it.value == slot }?.key
            id?.let { lastContactByPointerId[it] }
                ?: AirPlayContact(id = slot, x = 0.0, y = 0.0, down = false)
        }
}
