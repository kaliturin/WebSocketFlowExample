package com.example.websocketflowexample.ui.main.helpers.logger

import android.util.Log
import androidx.core.view.postDelayed
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.websocketflowexample.websocket.LogMessage
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.GroupieViewHolder
import timber.log.Timber

class RecyclerLoggerHelper(
    private val recyclerView: RecyclerView,
    private val prefix: String = ""
) {
    private val adapter = GroupAdapter<GroupieViewHolder>()

    init {
        recyclerView.adapter = adapter
    }

    private fun RecyclerView.isLastItemVisible(): Boolean {
        return (layoutManager as LinearLayoutManager)
            .findLastCompletelyVisibleItemPosition() + 2 >= (adapter?.itemCount ?: 0)
    }

    fun log(message: String, level: Int = Log.DEBUG) {
        val formatted = "$prefix(thread=${Thread.currentThread().id}) $message"
        Timber.d(formatted)
        log(LogMessage(formatted, level))
    }

    fun log(logMessage: LogMessage) {
        adapter.add(LogListItem(logMessage))
        with(recyclerView) {
            if (isLastItemVisible()) postDelayed(100) {
                smoothScrollToPosition(this@RecyclerLoggerHelper.adapter.itemCount)
            }
        }
    }
}