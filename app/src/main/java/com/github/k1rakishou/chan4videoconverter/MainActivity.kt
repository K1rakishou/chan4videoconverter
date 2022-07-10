package com.github.k1rakishou.chan4videoconverter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Card
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
  private val mainActivityViewModel by viewModels<MainActivityViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    lifecycleScope.launch {
      mainActivityViewModel.finishActivityFlow.collect { finishAndRemoveTask() }
    }

    lifecycleScope.launch {
      mainActivityViewModel.toastFlow.collect { errorText ->
        Toast.makeText(this@MainActivity, errorText, Toast.LENGTH_SHORT).show()
      }
    }

    lifecycleScope.launch {
      mainActivityViewModel.onNewIntent(intent)
    }

    setContent {
      MaterialTheme {
        val mediaToConvert = mainActivityViewModel.mediaToConvert

        Surface {
          ConvertableMediaList(mediaToConvert)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)

    lifecycleScope.launch {
      mainActivityViewModel.onNewIntent(intent)
    }
  }

  @Composable
  private fun ConvertableMediaList(mediaToConvert: List<MainActivityViewModel.ConvertableMedia>) {
    var currentConversionJob by remember { mutableStateOf<Job?>(null) }
    val buttonEnabled = mediaToConvert.any { media -> media.canBeEncoded }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        content = {
          items(
            count = mediaToConvert.size,
            key = { index -> mediaToConvert[index].inputFilePath },
            itemContent = { index -> ConvertableMediaItem(mediaToConvert[index]) }
          )
        }
      )

      if (mediaToConvert.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
          modifier = Modifier.align(Alignment.CenterHorizontally),
          enabled = buttonEnabled,
          onClick = {
            if (currentConversionJob != null) {
              currentConversionJob?.cancel()
              currentConversionJob = null
            } else {
              currentConversionJob = coroutineScope.launch {
                try {
                  mainActivityViewModel.convertMedia()
                } finally {
                  currentConversionJob = null
                }
              }
            }
          },
          content = {
            if (currentConversionJob == null) {
              Text(text = "Convert")
            } else {
              Text(text = "Cancel")
            }
          }
        )

        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }

  @Composable
  private fun ConvertableMediaItem(convertableMedia: MainActivityViewModel.ConvertableMedia) {
    val conversionParamsMut by convertableMedia.paramsState
    val conversionParams = conversionParamsMut

    val errorMut by convertableMedia.errorState
    val error = errorMut

    val encodingProgress by convertableMedia.encodingProgressState

    Card(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight()
        .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .wrapContentHeight()
      ) {
        val inputFilePathText = remember(key1 = convertableMedia.inputFilePath) {
          "Input file: ${convertableMedia.inputFile.name}"
        }

        Text(text = inputFilePathText, fontSize = 14.sp)

        if (conversionParams != null && conversionParams.isNotEmpty()) {
          Spacer(modifier = Modifier.height(4.dp))

          val conversionParamsText = remember(key1 = conversionParams) {
            "FFMpeg params: ${conversionParams}"
          }
          Text(text = conversionParamsText, fontSize = 14.sp)
        }

        if (error != null && error.isNotEmpty()) {
          Spacer(modifier = Modifier.height(4.dp))

          val errorText = remember(key1 = error) {
            "Error: ${error}"
          }
          Text(text = errorText, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(progress = encodingProgress)
      }
    }
  }

  companion object {
    private const val TAG = "TestActivity"
  }

}