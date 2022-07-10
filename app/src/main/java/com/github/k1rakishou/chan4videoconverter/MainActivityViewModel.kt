package com.github.k1rakishou.chan4videoconverter

import android.app.Application
import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.github.k1rakishou.chan4videoconverter.shared.CONVERT_MEDIA_ACTION
import com.github.k1rakishou.chan4videoconverter.shared.MEDIA_FILES_TO_CONVERT
import com.github.k1rakishou.chan4videoconverter.shared.MediaFileToConvert
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
  private val _mediaToConvert = mutableStateListOf<ConvertableMedia>()

  val mediaToConvert: List<ConvertableMedia>
    get() = _mediaToConvert

  private val _toastFlow = MutableSharedFlow<String>(extraBufferCapacity = 32)
  val toastFlow: SharedFlow<String>
    get() = _toastFlow.asSharedFlow()

  private val _finishActivityFlow = MutableSharedFlow<Unit>()
  val finishActivityFlow: SharedFlow<Unit>
    get() = _finishActivityFlow.asSharedFlow()

  private val newIntentActor = viewModelScope.actor<Intent?>(
    context = Dispatchers.IO,
    capacity = Channel.UNLIMITED
  ) {
    for (intent in this) {
      if (intent != null) {
        when (val action = intent.action) {
          CONVERT_MEDIA_ACTION -> {
            onConvertVideoToWebmIntentReceived(intent)
          }
          else -> {
            _toastFlow.emit("Unknown action: ${action}")
          }
        }
      }

      // If we failed to add anything when processing the intent then finish the activity right away
      if (_mediaToConvert.isEmpty()) {
        _finishActivityFlow.emit(Unit)
      }
    }
  }

  suspend fun onNewIntent(intent: Intent?) {
    newIntentActor.send(intent)
  }

  private suspend fun onConvertVideoToWebmIntentReceived(intent: Intent) {
    val mediaFilesToConvert = intent.getParcelableArrayListExtra<MediaFileToConvert>(MEDIA_FILES_TO_CONVERT) ?: emptyList()

    mediaFilesToConvert.forEach { mediaFileToConvert ->
      val inputFile = File(mediaFileToConvert.inputFilePath)
      val outputFile = File(mediaFileToConvert.outputFilePath)

      val indexOfPrev = _mediaToConvert
        .indexOfLast { convertableMedia -> convertableMedia.inputFilePath == mediaFileToConvert.inputFilePath }

      val convertableMedia = if (indexOfPrev >= 0) {
        _mediaToConvert[indexOfPrev]
      } else {
        val newConvertableMedia = ConvertableMedia(inputFile, outputFile)
        _mediaToConvert += newConvertableMedia

        newConvertableMedia
      }

      // Can't update convertableMedia if it's in the encoding process already
      if (convertableMedia.encodingInProgress) {
        return@forEach
      }

      convertableMedia.updateEncodingProgress(0f)

      if (!inputFile.exists() || !inputFile.canRead()) {
        convertableMedia.updateError("Input file is inaccessible")
        return@forEach
      }

      val extension = inputFile.extension
      val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

      if (extension.isBlank() || mimeType.isNullOrBlank()) {
        convertableMedia.updateError("Cannot figure out the mime type of the file by it's extension " +
          "(Only Video and Image are supported), extension: '$extension'")
        return@forEach
      }

      val mediaTypeRaw = mimeType.split("/").firstOrNull()

      val mediaType = when (mediaTypeRaw?.uppercase(Locale.ENGLISH)) {
        "VIDEO" -> MediaType.Video
        "IMAGE" -> MediaType.Image
        else -> {
          convertableMedia.updateError("Unsupported media type: '${mediaTypeRaw}'")
          return@forEach
        }
      }

      convertableMedia.updateMediaType(mediaType)
    }
  }

  suspend fun convertMedia() {
    val mediaToConvert = mediaToConvert.filter { convertableMedia -> convertableMedia.canBeEncoded }
    if (mediaToConvert.isEmpty()) {
      // TODO(KurobaEx): finish activity?
      return
    }

    coroutineScope {
      mediaToConvert.forEach { convertableMedia ->
        when (convertableMedia.mediaTypeState.value) {
          MediaType.Video -> {
            convertToWebm(convertableMedia, false, null)
          }
          MediaType.Image -> TODO()
          MediaType.Unknown -> {
            // TODO(KurobaEx):
          }
        }
      }
    }
  }

  private suspend fun CoroutineScope.convertToWebm(
    convertableMedia: ConvertableMedia,
    vp9: Boolean,
    opus: Boolean?
  ): Boolean {
    val inputFilePath = convertableMedia.inputFilePath
    val outputFilePath = convertableMedia.outputFilePath
    val mediaInformation = FFprobeKit.getMediaInformation(inputFilePath).mediaInformation

    Log.d(TAG, "bitrate: ${mediaInformation.bitrate}, duration: ${mediaInformation.duration}")
    mediaInformation.streams.forEach { streamInformation ->
      Log.d(TAG, "format: ${streamInformation.format}, codec: ${streamInformation.codec}")
    }

    val coresToUse = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)

    val video = if (vp9) {
      "-c:v libvpx-vp9"
    } else {
      "-c:v libvpx"
    }

    val audio = if (opus == null) {
      "-an"
    } else if (opus) {
      "-c:a libopus"
    } else {
      "-c:a libvorbis"
    }

    val fullCommand = "-i ${inputFilePath} ${audio} ${video} ${outputFilePath} -cpu-used ${coresToUse}"
    Log.d(TAG, "Running \'${fullCommand}\'...")

    suspendCancellableCoroutine<ConversionResult> { continuation ->
      convertableMedia.onEncodingStarted()

      val session = FFmpegKit.executeAsync(
        fullCommand,
        { session ->
          if (ReturnCode.isSuccess(session.returnCode)) {
            Log.d(TAG, "Running \'${fullCommand}\'... Success")
            continuation.resume(ConversionResult.Success)
          } else if (ReturnCode.isCancel(session.returnCode)) {
            Log.d(TAG, "Running \'${fullCommand}\'... Error (code: ${session.returnCode})")
            continuation.resume(ConversionResult.Canceled)
          } else {
            val errorMessage = String.format(
              "Running '${fullCommand}'... FAILURE! State: %s, rc: %s.%s",
              session.state, session.returnCode, session.failStackTrace
            )

            Log.e(TAG, errorMessage)
            continuation.resume(ConversionResult.Error(errorMessage))
          }
        },
        { /**Log is not used*/ },
        { statistics ->
          val progress = statistics.time.toFloat() / mediaInformation.duration.toFloat()
          convertableMedia.updateEncodingProgress(progress)
        }
      )

      continuation.invokeOnCancellation {
        if (it == null) {
          // Finished normally
          convertableMedia.onEncodingFinished()
          return@invokeOnCancellation
        }

        session.cancel()
        convertableMedia.onEncodingCanceled()
      }

    }

    return false
  }

  @Stable
  class ConvertableMedia(
    val inputFile: File,
    val outputFile: File
  ) {
    private val _paramsState = mutableStateOf<String?>(null)
    private val _errorState = mutableStateOf<String?>(null)
    private val _mediaTypeState = mutableStateOf<MediaType>(MediaType.Unknown)
    private val encodingInfo = EncodingInfo()

    val inputFilePath: String = inputFile.absolutePath
    val outputFilePath: String = outputFile.absolutePath

    val paramsState: State<String?>
      get() = _paramsState
    val errorState: State<String?>
      get() = _errorState
    val mediaTypeState: State<MediaType>
      get() = _mediaTypeState
    val encodingProgressState: State<Float>
      get() = encodingInfo.encodingProgressState

    val conversionFinished: Boolean
      get() = encodingInfo.conversionFinished
    val encodingInProgress: Boolean
      get() = encodingInfo.encodingInProgress
    val canBeEncoded: Boolean
      get() = !conversionFinished && !encodingInProgress

    fun updateError(error: String?) {
      _errorState.value = error
    }

    fun updateMediaType(mediaType: MediaType) {
      _mediaTypeState.value = mediaType
    }

    fun updateEncodingProgress(progress: Float) {
      encodingInfo.updateEncodingProgress(progress)
    }

    fun onEncodingStarted() {
      encodingInfo.onEncodingStarted()
    }

    fun onEncodingCanceled() {
      encodingInfo.onEncodingCanceled()
    }

    fun onEncodingFinished() {
      encodingInfo.onEncodingFinished()
    }

  }

  sealed interface ConversionResult {
    object Canceled : ConversionResult
    object Success : ConversionResult
    class Error(val errorMessage: String) : ConversionResult
  }

  class EncodingInfo {
    private val _encodingState = mutableStateOf<EncodingState?>(null)
    private val _encodingProgressState = mutableStateOf<Float>(0f)

    val encodingInProgress: Boolean
      get() = _encodingState.value != null && _encodingState.value !is EncodingState.Finished
    val encodingProgressState: State<Float>
      get() = _encodingProgressState
    val conversionFinished: Boolean
      get() = _encodingState.value != null && _encodingState.value is EncodingState.Finished

    fun onEncodingStarted() {
      check(_encodingState.value == null) {
        "Bad encodingState: ${_encodingState.value}, must be null"
      }

      _encodingState.value = EncodingState.Started
    }

    fun updateEncodingProgress(progress: Float) {
      check(_encodingState.value is EncodingState.Started) {
        "Bad encodingState: ${_encodingState.value}, must be EncodingState.Started"
      }

      _encodingProgressState.value = progress
    }

    fun onEncodingCanceled() {
      check(_encodingState.value is EncodingState.Started) {
        "Bad encodingState: ${_encodingState.value}, must be EncodingState.Started"
      }

      _encodingState.value = null
      _encodingProgressState.value = 0f
    }

    fun onEncodingFinished() {
      check(_encodingState.value is EncodingState.Started) {
        "Bad encodingState: ${_encodingState.value}, must be EncodingState.Started"
      }

      _encodingState.value = EncodingState.Finished
      _encodingProgressState.value = 0f
    }

  }

  sealed interface EncodingState {
    object Started : EncodingState
    object Finished : EncodingState
  }

  enum class MediaType {
    Video,
    Image,
    Unknown
  }

  companion object {
    private const val TAG = "MainActivityViewModel"
  }

}