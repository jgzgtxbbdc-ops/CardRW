package com.cardrw.app.nfc

import android.nfc.Tag
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bus global des tags NFC : [enableReaderMode] vit dans [com.cardrw.app.MainActivity]
 * (toute l’app au premier plan → pas de chooser système).
 *
 * [consumePending] sert quand on navigue vers Carte après un tag (abonné tardif).
 */
@Singleton
class NfcTagBus @Inject constructor() {

    private val pending = AtomicReference<Tag?>(null)

    private val _tags = MutableSharedFlow<Tag>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val tags: SharedFlow<Tag> = _tags.asSharedFlow()

    fun emit(tag: Tag) {
        pending.set(tag)
        _tags.tryEmit(tag)
    }

    /** Récupère et efface le dernier tag (démarrage CardViewModel après navigation). */
    fun consumePending(): Tag? = pending.getAndSet(null)

    /** Efface le pending s’il correspond encore à ce tag (après traitement via [tags]). */
    fun clearPending(tag: Tag) {
        pending.compareAndSet(tag, null)
    }
}
