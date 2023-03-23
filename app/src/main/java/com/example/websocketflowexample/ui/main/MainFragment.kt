package com.example.websocketflowexample.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.example.websocketflowexample.MainActivity
import com.example.websocketflowexample.R
import com.example.websocketflowexample.databinding.FragmentMainBinding
import com.example.websocketflowexample.ui.main.helpers.logger.RecyclerLoggerHelper
import com.example.websocketflowexample.websocket.WebSocketFlow
import com.example.websocketflowexample.websocket.impl.SocketResponse
import com.example.websocketflowexample.websocket.impl.SocketResponseGenerator
import org.koin.android.ext.android.inject

class MainFragment : Fragment(R.layout.fragment_main) {
    private val binding by viewBinding(FragmentMainBinding::bind)
    private val webSocket: WebSocketFlow<SocketResponse> by inject()
    private val socketResponseGenerator = SocketResponseGenerator()
    private lateinit var logger: RecyclerLoggerHelper

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        logger = RecyclerLoggerHelper(binding.logRecyclerView, "Client")
        webSocket.listenLog(lifecycleScope) { logger.log(it) }

        binding.subscribeButton.setOnClickListener {
            webSocket.subscribe(viewLifecycleOwner) {
                if (it.error != null)
                    logger.e("onFailure: ${it.error}")
                else
                    logger.d("onMessage: $it")
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
            webSocket.send(socketResponseGenerator.generate().toString())
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

    companion object {
        private const val DERIVED = "derived"
    }
}