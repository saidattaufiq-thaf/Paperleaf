package com.paperleaf.sketchbook.asset

data class AssetData(
    val id: String,
    val name: String,
    val category: AssetCategory,
    val version: Int,
    val author: String,
    val thumbnail: String = "",
    val preview: String = "",
    var installed: Boolean = false,
    var enabled: Boolean = true
)
