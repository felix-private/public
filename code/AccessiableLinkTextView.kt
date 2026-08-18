package com.example.accessibility

import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.TextView

data class AccessibleLink(
    val start: Int,
    val end: Int,
    val url: String,
    val accessibilityDescription: String
)

class AccessibleLinkTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : TextView(context, attrs, defStyleAttr) {

    companion object {
        private const val VIRTUAL_ID_TEXT = 1
        private const val VIRTUAL_ID_LINK_BASE = 1000
    }

    private var accessibleLinks: List<AccessibleLink> = emptyList()

    private val nodeProvider = LinkAccessibilityNodeProvider()

    init {
        movementMethod = LinkMovementMethod.getInstance()

        isFocusable = true
    }

    fun setAccessibleText(
        text: CharSequence,
        links: List<AccessibleLink>
    ) {
        require(
            links.all {
                it.start >= 0 &&
                    it.end <= text.length &&
                    it.start < it.end
            }
        ) {
            "Invalid link range"
        }

        require(
            links.zipWithNext().all { (a, b) ->
                a.end <= b.start
            }
        ) {
            "Links must not overlap"
        }

        accessibleLinks = links

        val spannable = SpannableString(text)

        links.forEach { link ->
            spannable.setSpan(
                LinkSpan(link),
                link.start,
                link.end,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        this.text = spannable

        sendAccessibilityEvent(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )

        invalidate()
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider {
        return nodeProvider
    }

    private fun performLinkClick(link: AccessibleLink) {
        // 在这里处理真正的 URL 点击。
        //
        // 例如：
        //
        // val intent = Intent(
        //     Intent.ACTION_VIEW,
        //     Uri.parse(link.url)
        // )
        // context.startActivity(intent)
    }

    private inner class LinkSpan(
        private val link: AccessibleLink
    ) : ClickableSpan() {

        override fun onClick(widget: View) {
            performLinkClick(link)
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.isUnderlineText = true
        }
    }

    private inner class LinkAccessibilityNodeProvider :
        AccessibilityNodeProvider() {

        private var accessibilityFocusedVirtualViewId =
            View.NO_ID

        override fun createAccessibilityNodeInfo(
            virtualViewId: Int
        ): AccessibilityNodeInfo? {

            return if (virtualViewId == View.NO_ID) {
                createHostNode()
            } else {
                createVirtualNode(virtualViewId)
            }
        }

        override fun performAction(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {

            return when (virtualViewId) {

                View.NO_ID -> {
                    super.performAction(
                        virtualViewId,
                        action,
                        arguments
                    )
                }

                else -> {
                    performVirtualViewAction(
                        virtualViewId,
                        action,
                        arguments
                    )
                }
            }
        }

        private fun createHostNode(): AccessibilityNodeInfo {
            val info = AccessibilityNodeInfo.obtain(this@AccessibleLinkTextView)

            info.className =
                AccessibleLinkTextView::class.java.name

            info.packageName =
                context.packageName

            info.isEnabled = isEnabled
            info.isVisibleToUser = visibility == VISIBLE

            /*
             * Host node owns the virtual children.
             */
            accessibleLinks.forEachIndexed { index, _ ->
                info.addChild(
                    this@AccessibleLinkTextView,
                    virtualIdForLink(index)
                )
            }

            return info
        }

        private fun createVirtualNode(
            virtualViewId: Int
        ): AccessibilityNodeInfo? {

            val linkIndex =
                linkIndexFromVirtualId(virtualViewId)

            if (linkIndex !in accessibleLinks.indices) {
                return null
            }

            val link = accessibleLinks[linkIndex]

            val info = AccessibilityNodeInfo.obtain()

            info.packageName =
                context.packageName

            info.className =
                "android.widget.TextView"

            info.setSource(
                this@AccessibleLinkTextView,
                virtualViewId
            )

            info.setParent(
                this@AccessibleLinkTextView
            )

            info.isVisibleToUser = true
            info.isEnabled = isEnabled

            /*
             * What is visually displayed.
             */
            val visibleText =
                text.subSequence(
                    link.start,
                    link.end
                ).toString()

            info.text = visibleText

            /*
             * What TalkBack should additionally announce.
             */
            info.contentDescription =
                if (link.accessibilityDescription.isBlank()) {
                    visibleText
                } else {
                    "$visibleText ${link.accessibilityDescription}"
                }

            /*
             * This is a clickable accessibility node.
             */
            info.isClickable = true

            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    "打开链接"
                )
            )

            /*
             * Accessibility focus.
             */
            if (accessibilityFocusedVirtualViewId ==
                virtualViewId
            ) {
                info.isAccessibilityFocused = true

                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    )
                )
            } else {
                info.isAccessibilityFocused = false

                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                    )
                )
            }

            /*
             * Bounds of the link in the TextView.
             */
            val bounds = getTextBounds(
                link.start,
                link.end
            )

            info.setBoundsInParent(bounds)

            return info
        }

        private fun performVirtualViewAction(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {

            val linkIndex =
                linkIndexFromVirtualId(virtualViewId)

            if (linkIndex !in accessibleLinks.indices) {
                return false
            }

            val link = accessibleLinks[linkIndex]

            return when (action) {

                AccessibilityNodeInfo.ACTION_CLICK -> {
                    performLinkClick(link)

                    sendVirtualViewEvent(
                        virtualViewId,
                        AccessibilityEvent.TYPE_VIEW_CLICKED
                    )

                    true
                }

                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocusedVirtualViewId ==
                        virtualViewId
                    ) {
                        false
                    } else {
                        accessibilityFocusedVirtualViewId =
                            virtualViewId

                        sendVirtualViewEvent(
                            virtualViewId,
                            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                        )

                        true
                    }
                }

                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocusedVirtualViewId !=
                        virtualViewId
                    ) {
                        false
                    } else {
                        accessibilityFocusedVirtualViewId =
                            View.NO_ID

                        sendVirtualViewEvent(
                            virtualViewId,
                            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
                        )

                        true
                    }
                }

                else -> false
            }
        }

        private fun sendVirtualViewEvent(
            virtualViewId: Int,
            eventType: Int
        ) {
            val event = AccessibilityEvent.obtain(eventType)

            event.packageName =
                context.packageName

            event.className =
                AccessibleLinkTextView::class.java.name

            event.setSource(
                this@AccessibleLinkTextView,
                virtualViewId
            )

            sendAccessibilityEventUnchecked(event)
        }
    }

    private fun virtualIdForLink(
        index: Int
    ): Int {
        return VIRTUAL_ID_LINK_BASE + index
    }

    private fun linkIndexFromVirtualId(
        virtualViewId: Int
    ): Int {
        return virtualViewId - VIRTUAL_ID_LINK_BASE
    }

    /**
     * Calculate the visual bounds of a text range.
     *
     * Works with TextView's Layout, including
     * wrapped/multi-line text.
     */
    private fun getTextBounds(
        start: Int,
        end: Int
    ): Rect {

        val layout = layout
            ?: return Rect()

        if (start >= end) {
            return Rect()
        }

        val path = Path()

        layout.getSelectionPath(
            start,
            end,
            path
        )

        val bounds = RectF()

        path.computeBounds(
            bounds,
            true
        )

        /*
         * TextView's layout coordinates are relative
         * to the text content.
         */
        bounds.offset(
            compoundPaddingLeft.toFloat(),
            compoundPaddingTop.toFloat()
        )

        bounds.offset(
            -scrollX.toFloat(),
            -scrollY.toFloat()
        )

        return Rect(
            bounds.left.toInt(),
            bounds.top.toInt(),
            bounds.right.toInt(),
            bounds.bottom.toInt()
        )
    }
}