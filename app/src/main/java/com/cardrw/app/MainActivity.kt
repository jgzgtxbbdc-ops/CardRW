package com.cardrw.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.cardrw.app.nfc.NfcReaderController
import com.cardrw.app.nfc.NfcTagBus
import com.cardrw.app.ui.navigation.CardRwNavHost
import com.cardrw.app.ui.theme.CardRwTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Reader mode NFC actif **tant que l’Activity est au premier plan**
 * (évite le chooser système « Tag NFC détecté » sur Accueil / Coffre / …).
 * Les tags sont diffusés via [NfcTagBus] ; l’écran Carte les consomme (option B : navigation auto).
 *
 * [FragmentActivity] requis pour BiometricPrompt (K3 coffre).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var tagBus: NfcTagBus

    private var nfcController: NfcReaderController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CardRwTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CardRwNavHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val controller = NfcReaderController(this) { tag ->
            tagBus.emit(tag)
        }
        nfcController = controller
        if (controller.isAvailable && controller.isEnabled) {
            controller.start()
        }
    }

    override fun onPause() {
        nfcController?.stop()
        nfcController = null
        super.onPause()
    }
}
