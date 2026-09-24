package com.v2ray.ang.dto

import com.google.gson.annotations.SerializedName

data class GitHubRelease(
    @SerializedName("tag_name")
    val tagName: String,
    @SerializedName("body")
    val body: String,
    @SerializedName("assets")
    val assets: List<Asset>,
    @SerializedName("prerelease")
    val prerelease: Boolean = false,
    @SerializedName("published_at")
    val publishedAt: String = ""
) {
    data class Asset(
        @SerializedName("id")
        val id: Long = 0,
        @SerializedName("name")
        val name: String,
        @SerializedName("size")
        val size: Long = 0,
        @SerializedName("digest")
        val digest: String? = null,
        @SerializedName("browser_download_url")
        val browserDownloadUrl: String
    )
}
