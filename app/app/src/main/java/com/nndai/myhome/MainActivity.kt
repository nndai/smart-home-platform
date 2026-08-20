package com.nndai.myhome

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nndai.myhome.core.theme.RemotePumpTheme
import com.nndai.myhome.core.utils.LocaleHelper
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.presentation.navigation.AppNavigation

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        PumpRepositoryProvider.init(applicationContext)
        
        enableEdgeToEdge()
        setContent {
            RemotePumpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}
