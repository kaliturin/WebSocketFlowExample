package com.example.websocketflowexample.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
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
import com.example.websocketflowexample.websocket.impl.SocketResponse
import com.example.websocketflowexample.websocket.impl.SocketResponseBuilder
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.GroupieViewHolder
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.*

class MainFragment : Fragment(R.layout.fragment_main) {
    private val binding by viewBinding(FragmentMainBinding::bind)
    private val webSocket: WebSocketFlow<SocketResponse> by inject()
    private val logRecyclerAdapter = GroupAdapter<GroupieViewHolder>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.logRecyclerView.adapter = logRecyclerAdapter
        webSocket.listenLog(lifecycleScope) { log(it) }

        binding.subscribeButton.setOnClickListener {
            webSocket.subscribe(viewLifecycleOwner) {
                if (it.error != null)
                    log("onFailure: ${it.error}", Log.ERROR)
                else
                    log("onMessage: $it")
            }
        }

        binding.unsubscribeButton.setOnClickListener {
            webSocket.unsubscribe(viewLifecycleOwner)
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
            webSocket.send(generateSocketResponse().toString())
        }

        binding.startLifecycleButton.setOnClickListener {
            val intent = Intent(requireContext(), MainActivity::class.java)
            intent.putExtra(DERIVED, true)
            startActivity(intent)
        }

        binding.stopLifecycleButton.isVisible =
            activity?.intent?.getBooleanExtra(DERIVED, false) == true

        binding.stopLifecycleButton.setOnClickListener {
            activity?.finish()
        }
    }

    private fun RecyclerView.isLastItemVisible(): Boolean {
        return (layoutManager as LinearLayoutManager)
            .findLastCompletelyVisibleItemPosition() + 2 >= (adapter?.itemCount ?: 0)
    }

    private fun log(message: String, level: Int = Log.DEBUG) {
        val formatted = "Client(thread=${Thread.currentThread().id}) $message"
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

    private fun generateSocketResponse(): SocketResponse {
        val r = Random()
        val builder = SocketResponseBuilder()
        if (r.nextBoolean()) {
            fun i() = r.nextInt(10000)
            builder.header(SocketResponse.Header.Type.INT_DATA)
                .body(intData = listOf(i(), i(), i()))
        } else {
            fun s() = UUID.randomUUID().toString()
                .replace("[0-9-]".toRegex(), "").uppercase().take(4)
            builder.header(SocketResponse.Header.Type.STRING_DATA)
                .body(strData = listOf(s(), s(), s()))
        }
        return builder.build()
    }

    companion object {
        private const val DERIVED = "derived"
    }
}