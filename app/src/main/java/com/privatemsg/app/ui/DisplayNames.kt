package com.privatemsg.app.ui

/**
 * The rules that decide which name a conversation row shows.
 *
 * Kept free of Android types on purpose: the rules can then be unit-tested
 * without a device. They also hold the privacy rule of the hidden list in one
 * place, so it can be checked by a test instead of by reading the screen.
 */
object DisplayNames {

    /**
     * The name a row shows for [address].
     *
     * @param label the name the app itself gave this number (the alias of a hidden
     *   number). It is the only name a hidden row may ever show.
     * @param cached the contact name behind the number, if it is known.
     * @param hidden true for a list that is built around a private number.
     *
     * A hidden row never falls back to the contact name: the number behind a hidden
     * contact must not be revealed by accident, so a hidden row without a label
     * shows the number itself and nothing else.
     */
    fun forRow(address: String, label: String?, cached: String?, hidden: Boolean = false): String {
        val own = label?.trim()
        if (own != null && own.isNotEmpty()) return own
        if (hidden) return address
        val contact = cached?.trim()
        return if (contact != null && contact.isNotEmpty()) contact else address
    }

    /**
     * Whether a row of this list may show the contact photo.
     *
     * @param ownLabels true for a list that labels its rows itself (the hidden one).
     *
     * A list with its own labels must not show a photo: the face behind a hidden
     * number would give away who it is, which is exactly what the hidden list
     * exists to keep secret. The rule sits next to the name rule so the two are
     * checked together instead of one of them being forgotten.
     */
    fun showsPhoto(ownLabels: Boolean): Boolean = !ownLabels
}
