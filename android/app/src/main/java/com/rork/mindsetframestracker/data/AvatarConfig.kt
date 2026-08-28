package com.rork.mindsetframestracker.data

import kotlinx.serialization.Serializable

/**
 * The user's companion avatar configuration. Every field is an index into
 * the corresponding option list in `AvatarCatalog`; indices are always
 * resolved modulo the list size so old data can never crash a newer catalog.
 *
 * Core customization is free for everyone. Circular background frames are
 * earned through streak achievements ([BadgeTier]); exclusive outfits,
 * expressions, and pets are earned by completing daily tasks in the app
 * (see `CompanionTask`) — never sold.
 */
@Serializable
data class AvatarConfig(
    val skinTone: Int = 2,
    val faceShape: Int = 0,
    val eyes: Int = 1,
    val mouth: Int = 0,
    val hair: Int = 2,
    val hairColor: Int = 1,
    val outfit: Int = 0,
    val companion: Int = 1,
    val frame: Int = 0,
    /** 0 = female model, 1 = male model. Same art style, different build. */
    val gender: Int = 0,
    /**
     * Facial expression preset. 0 = "custom" (renders the [eyes] and [mouth]
     * selections); 1+ picks a full preset (smiling, winking, neutral,
     * focused, …) with its own unique eye/mouth artwork.
     */
    val expression: Int = 0,
)
