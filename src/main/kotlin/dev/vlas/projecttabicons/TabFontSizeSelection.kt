package dev.vlas.projecttabicons

/** Отличает фиксированный размер от автоматического, даже когда их числа совпадают. */
internal class TabFontSizeSelection {
    var storedSize: Int = 0
        private set
    private var displayedSize = 0

    fun reset(storedSize: Int, defaultSize: Int): Int {
        this.storedSize = storedSize
        displayedSize = if (storedSize == 0) defaultSize else storedSize
        return displayedSize
    }

    fun select(size: Int, defaultSize: Int) {
        if (size == displayedSize) return
        displayedSize = size
        storedSize = if (size == defaultSize) 0 else size
    }
}
