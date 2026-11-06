package de.bewerbung.jobfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.bewerbung.jobfinder.ui.AppRoot
import de.bewerbung.jobfinder.ui.JobFinderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JobFinderTheme {
                AppRoot()
            }
        }
    }
}
