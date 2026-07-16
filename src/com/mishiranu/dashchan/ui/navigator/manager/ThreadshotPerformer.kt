package com.mishiranu.dashchan.ui.navigator.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import chan.util.CommonUtils
import com.mishiranu.dashchan.R
import com.mishiranu.dashchan.content.MainApplication
import com.mishiranu.dashchan.content.async.ExecutorTask
import com.mishiranu.dashchan.content.async.TaskViewModel
import com.mishiranu.dashchan.content.model.PostItem
import com.mishiranu.dashchan.ui.InstanceDialog
import com.mishiranu.dashchan.util.ConcurrentUtils
import com.mishiranu.dashchan.util.ResourceUtils
import com.mishiranu.dashchan.widget.ClickableToast
import com.mishiranu.dashchan.widget.ProgressDialog
import com.mishiranu.dashchan.widget.ThemeEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ThreadshotPerformer(
    fragmentManager: FragmentManager,
    chanName: String,
    boardName: String?,
    threadNumber: String?,
    threadTitle: String?,
    private val postItems: List<PostItem>,
    private val width: Int,
) {
    private val uiManager: UiManager
    private val chanName: String = chanName
    private val holder: RecyclerView.ViewHolder
    private val divider: Drawable?
    private val background: Int

    private var viewModel: ThreadshotViewModel? = null

    private val task: ExecutorTask<Unit, InputStream?> =
        object : ExecutorTask<Unit, InputStream?>() {
            override fun run(): InputStream? {
                val time = SystemClock.elapsedRealtime()
                val configurationSet =
                    UiManager.ConfigurationSet(
                        this@ThreadshotPerformer.chanName,
                        null,
                        null,
                        UiManager.PostStateProvider.DEFAULT,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null,
                    )
                val demandSet = UiManager.DemandSet()
                demandSet.selection = UiManager.Selection.THREADSHOT
                val dividerHeight = divider?.intrinsicHeight ?: 0
                val dividerPadding = (12f * ResourceUtils.obtainDensity(uiManager.context)).toInt()
                var height = 0
                var first = true
                val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                for (postItem in postItems) {
                    val measuredHeight =
                        ConcurrentUtils.mainGet {
                            uiManager.view().bindPostView(holder, postItem, configurationSet, demandSet)
                            holder.itemView.measure(widthMeasureSpec, heightMeasureSpec)
                            holder.itemView.measuredHeight
                        }!!
                    if (!first) {
                        height += dividerHeight
                    } else {
                        first = false
                    }
                    height += measuredHeight
                }
                if (isCancelled()) {
                    return null
                }
                var input: InputStream? = null
                if (height > 0) {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(background)
                    for (postItem in postItems) {
                        if (isCancelled()) {
                            return null
                        }
                        ConcurrentUtils.mainGet<Any?> {
                            uiManager.view().bindPostView(holder, postItem, configurationSet, demandSet)
                            holder.itemView.measure(widthMeasureSpec, heightMeasureSpec)
                            holder.itemView.layout(
                                0,
                                0,
                                holder.itemView.measuredWidth,
                                holder.itemView.measuredHeight,
                            )
                            holder.itemView.draw(canvas)
                            canvas.translate(0f, holder.itemView.height.toFloat())
                            if (divider != null && dividerHeight > 0) {
                                divider.setBounds(dividerPadding, 0, width - dividerPadding, dividerHeight)
                                divider.draw(canvas)
                                canvas.translate(0f, dividerHeight.toFloat())
                            }
                            null
                        }
                    }
                    val output = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    bitmap.recycle()
                    input = ByteArrayInputStream(output.toByteArray())
                }
                CommonUtils.sleepMaxRealtime(time, 500)
                return input
            }

            override fun onComplete(result: InputStream?) {
                viewModel!!.handleResult(result)
            }
        }

    init {
        val context: Context =
            ThemeEngine.attach(
                ContextThemeWrapper(MainApplication.getInstance().localizedContext, 0),
            )
        ThemeEngine.applyTheme(context)
        uiManager = UiManager(context, null, null)
        holder = uiManager.view().createView(FrameLayout(context), ViewUnit.ViewType.POST)
        divider = ResourceUtils.getDrawable(context, android.R.attr.listDivider, 0)
        background = ThemeEngine.getColorScheme(context).windowBackgroundColor
        start(fragmentManager, chanName, boardName, threadNumber, threadTitle)
    }

    private fun start(
        fragmentManager: FragmentManager,
        chanName: String,
        boardName: String?,
        threadNumber: String?,
        threadTitle: String?,
    ) {
        InstanceDialog(fragmentManager, null) { provider ->
            val dialog = ProgressDialog(provider.context, null)
            dialog.setMessage(provider.context.getString(R.string.processing_data__ellipsis))
            val viewModel = provider.getViewModel(ThreadshotViewModel::class.java)
            if (!viewModel.hasTaskOrValue()) {
                this.viewModel = viewModel
                task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)
                viewModel.attach(task)
            }
            viewModel.observe(provider.lifecycleOwner) { result ->
                provider.dismiss()
                if (result != null) {
                    val binder = UiManager.extract(provider)!!.callback()!!.getDownloadBinder()
                    binder?.downloadStorage(
                        result,
                        chanName,
                        boardName,
                        threadNumber,
                        threadTitle,
                        "threadshot-" + System.currentTimeMillis() + ".png",
                        true,
                        false,
                    )
                } else {
                    ClickableToast.show(R.string.unknown_error)
                }
            }
            dialog
        }
    }

    class ThreadshotViewModel : TaskViewModel<ExecutorTask<Unit, InputStream?>, InputStream?>()
}
