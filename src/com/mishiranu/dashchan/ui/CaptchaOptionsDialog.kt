package com.mishiranu.dashchan.ui

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import androidx.core.util.Pair
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import chan.util.DataFile
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.util.GraphicsUtils
import com.mishiranu.dashchan.widget.ClickableToast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

class CaptchaOptionsDialog : DialogFragment {
	private lateinit var viewModel: CaptchaOptionsViewModel

	constructor()

	constructor(captchaImage: Bitmap) {
		val args = Bundle()
		args.putParcelable(KEY_CAPTCHA_IMAGE, captchaImage)
		arguments = args
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		viewModel = ViewModelProvider(this).get(CaptchaOptionsViewModel::class.java)
		observeCaptchaImageAttachment()
		observeCaptchaImageDownload()
	}

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val captchaImage = requireArguments().getParcelable<Bitmap>(KEY_CAPTCHA_IMAGE)!!
		val buttonTitles = arrayOf(getString(R.string.attach), getString(R.string.download_file),
				getString(R.string.refresh))
		val dialog = AlertDialog.Builder(requireContext()).setItems(buttonTitles, null).create()
		// Set onclick listener here to prevent the dialog from dismissing when an item is clicked,
		// we will dismiss the dialog manually when selected action is done
		dialog.listView.setOnItemClickListener { _, _, position, _ ->
			when (position) {
				0 -> viewModel.onAttachClicked(captchaImage)
				1 -> viewModel.onDownloadClicked(captchaImage)
				2 -> refreshCaptchaAndDismiss()
				else -> throw IllegalStateException("Unexpected value: $position")
			}
		}
		return dialog
	}

	private fun observeCaptchaImageAttachment() {
		viewModel.captchaImageAttachmentDataFile.observe(this) { captchaImageAttachmentDataFile ->
			if (captchaImageAttachmentDataFile != null) {
				getCallback().attachCaptchaImageToPost(captchaImageAttachmentDataFile)
			} else {
				ClickableToast.show(R.string.unknown_error)
			}
			dismiss()
		}
	}

	private fun observeCaptchaImageDownload() {
		viewModel.captchaImageDownloadInputStreamAndFileName.observe(this) { captchaImageDownloadInputStreamAndName ->
			val captchaImageInputStream = captchaImageDownloadInputStreamAndName.first
			val captchaImageName = captchaImageDownloadInputStreamAndName.second
			val captchaImageDownloadParameters = getCallback().getCaptchaImageDownloadParameters()
			val chanName = captchaImageDownloadParameters.chanName
			val boardName = captchaImageDownloadParameters.boardName
			val threadNumber = captchaImageDownloadParameters.threadNumber
			(requireActivity() as FragmentHandler).getDownloadBinder()!!.downloadStorage(captchaImageInputStream,
					chanName, boardName, threadNumber, null, captchaImageName, true, false)
			dismiss()
		}
	}

	private fun refreshCaptchaAndDismiss() {
		getCallback().refreshCaptcha()
		dismiss()
	}

	private fun getCallback(): Callback {
		val parentFragment = requireParentFragment()
		if (parentFragment is Callback) {
			return parentFragment
		} else {
			throw IllegalStateException("Parent fragment must implement CaptchaOptionsDialog.Callback")
		}
	}

	interface Callback {
		fun attachCaptchaImageToPost(captchaImageAttachmentDataFile: DataFile)
		fun getCaptchaImageDownloadParameters(): CaptchaImageDownloadParameters
		fun refreshCaptcha()
	}

	class CaptchaImageDownloadParameters(
			@JvmField val chanName: String?,
			@JvmField val boardName: String?,
			@JvmField val threadNumber: String?)

	class CaptchaOptionsViewModel : ViewModel() {
		internal val captchaImageAttachmentDataFile = MutableLiveData<DataFile?>()
		internal val captchaImageDownloadInputStreamAndFileName = MutableLiveData<Pair<InputStream, String>>()
		private val executor = Executors.newSingleThreadExecutor()

		internal fun onAttachClicked(captchaImage: Bitmap) {
			executor.execute {
				try {
					val captchaImageFileName = getCaptchaImageFileName()
					val captchaImageDataFile = DataFile.obtain(DataFile.Target.CACHE, captchaImageFileName)
					writeCaptchaImagePNGToOutputStream(captchaImage, captchaImageDataFile.openOutputStream())
					captchaImageAttachmentDataFile.postValue(captchaImageDataFile)
				} catch (e: IOException) {
					captchaImageAttachmentDataFile.postValue(null)
				}
			}
		}

		internal fun onDownloadClicked(captchaImage: Bitmap) {
			executor.execute {
				val captchaImageOutputStream = ByteArrayOutputStream()
				writeCaptchaImagePNGToOutputStream(captchaImage, captchaImageOutputStream)
				val captchaImageBytes = captchaImageOutputStream.toByteArray()
				val captchaImageInputStream = ByteArrayInputStream(captchaImageBytes)
				val captchaImageFileName = getCaptchaImageFileName()
				captchaImageDownloadInputStreamAndFileName.postValue(Pair(captchaImageInputStream, captchaImageFileName))
			}
		}

		private fun writeCaptchaImagePNGToOutputStream(captchaImage: Bitmap, outputStream: OutputStream) {
			processCaptchaImage(captchaImage).compress(Bitmap.CompressFormat.PNG, 0, outputStream)
		}

		private fun processCaptchaImage(captchaImage: Bitmap): Bitmap {
			return if (GraphicsUtils.isBlackAndWhiteCaptchaImage(captchaImage)) {
				createCaptchaImageWithWhiteBackground(captchaImage)
			} else {
				captchaImage
			}
		}

		private fun createCaptchaImageWithWhiteBackground(captchaImage: Bitmap): Bitmap {
			val newCaptchaImage = Bitmap.createBitmap(captchaImage.width, captchaImage.height, captchaImage.config!!)
			val canvas = Canvas(newCaptchaImage)
			canvas.drawColor(Color.WHITE)
			canvas.drawBitmap(captchaImage, 0f, 0f, null)
			return newCaptchaImage
		}

		private fun getCaptchaImageFileName(): String {
			return "captcha-" + System.currentTimeMillis() + ".png"
		}

		override fun onCleared() {
			executor.shutdownNow()
		}
	}

	companion object {
		private const val KEY_CAPTCHA_IMAGE = "captcha_image"
	}
}
