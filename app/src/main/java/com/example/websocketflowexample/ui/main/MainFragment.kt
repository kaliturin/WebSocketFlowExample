package com.example.websocketflowexample.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.example.websocketflowexample.MainActivity
import com.example.websocketflowexample.R
import com.example.websocketflowexample.databinding.FragmentMainBinding
import com.example.websocketflowexample.websocket.WebSocketFlow
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.GroupieViewHolder
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.*

class MainFragment : Fragment(R.layout.fragment_main) {
    private val binding by viewBinding(FragmentMainBinding::bind)
    private val webSocket: WebSocketFlow<String> by inject()
    private val logRecyclerAdapter = GroupAdapter<GroupieViewHolder>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.logRecyclerView.adapter = logRecyclerAdapter
        webSocket.listenLog(lifecycleScope) { log(it) }

        binding.connect1Button.setOnClickListener {
            // TODO: every click creates a new connection
            webSocket.listen(lifecycleScope) {
                log("client1 collected = $it")
            }
        }

        binding.connect2Button.setOnClickListener {
            // TODO: every click creates a new connection
            webSocket.listen(lifecycleScope) {
                log("client2 collected = $it")
            }
        }

        binding.pauseButton.setOnClickListener {
            webSocket.pause()
        }

        binding.closeButton.setOnClickListener {
            webSocket.close()
        }

        binding.resumeButton.setOnClickListener {
            webSocket.resume()
        }

        binding.sendButton.setOnClickListener {
            webSocket.send(Random().nextInt().toString())
        }

        binding.startLifecycleButton.setOnClickListener {
            startActivity(Intent(requireContext(), MainActivity::class.java))
        }

        binding.stopLifecycleButton.setOnClickListener {
            activity?.finish()
        }
    }

    private fun RecyclerView.isLastItemVisible(): Boolean {
        return (layoutManager as LinearLayoutManager)
            .findLastCompletelyVisibleItemPosition() + 2 >= (adapter?.itemCount ?: 0)
    }

    private fun log(message: String, level: Int = Log.DEBUG) {
        val formatted = "t${Thread.currentThread().id} $message"
        Timber.d(formatted)
        log(WebSocketFlow.LogMessage(formatted, level))
    }

    private fun log(logMessage: WebSocketFlow.LogMessage) {
        logRecyclerAdapter.add(LogListItem(logMessage))
        with(binding.logRecyclerView) {
            if (isLastItemVisible()) postDelayed(100) {
                smoothScrollToPosition(logRecyclerAdapter.itemCount)
            }
        }
    }
}