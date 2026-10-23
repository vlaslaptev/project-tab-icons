package dev.vlas.projecttabicons

import com.intellij.util.xmlb.annotations.OptionTag

/** Только параметры групп; размеры, цвета и обводка наследуются от обычных табов. */
internal data class GroupHighlightOptions(
    @field:OptionTag @JvmField val cardInset: Int = 3,
    @field:OptionTag @JvmField val showDividers: Boolean = true,
    @field:OptionTag @JvmField val dividerIntensity: Int = 32,
    @field:OptionTag @JvmField val dividerHeight: Int = 60,
    @field:OptionTag @JvmField val hideDividersNearActive: Boolean = false,
) {
    fun normalized(): GroupHighlightOptions = copy(
        cardInset = cardInset.coerceIn(0, 6), dividerIntensity = dividerIntensity.coerceIn(0, 100),
        dividerHeight = dividerHeight.coerceIn(20, 100),
    )
}
