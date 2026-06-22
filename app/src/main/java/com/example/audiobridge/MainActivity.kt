package com.example.audiobridge

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import android.view.View

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context

import android.media.*
import java.net.DatagramPacket
import java.net.DatagramSocket

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.ServerSocket
import java.net.Socket
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

class AudioService: Service() {

    private var isRunning = false
    private var UDPsocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null 
    private var thread_Audio: Thread? = null

    private var thread_Server: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null 
    private var reader: BufferedReader? = null 
    private var writer: BufferedWriter? = null

    private val sendQueue = LinkedBlockingQueue<JSONObject>()
    private var isConnect = false
    private var thread_queue: Thread? = null

    private val TAG = "ControlTCPServer"


    companion object {
        var instance: AudioService? = null
        var volume = 1.0F
        // var serverIP = "192.168.0.2"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent:Intent?,flags: Int, startId: Int): Int{
        startForeground(1,createNotification())

        when (intent?.action){
            "START" -> {
                isRunning = true
                startAudioReceiver()
				RunServer()
            }
            "STOP" -> {
                stopService()
                broadcastStopEvent()
                stopSelf()
            }
            "VOLUME_UP" -> {
                volume = minOf(volume + 0.1f, 2.0f)
                updateNotification()
                broadcastVolumeUpadte()
				val json = JSONObject()
				json.put("type","VOLUME")
				json.put("value",(volume * 100).toInt())
				sendQueue.put(json)
            }
            "VOLUME_DOWN" -> {
                volume = maxOf(volume - 0.1f, 0.0f)
                updateNotification()
                broadcastVolumeUpadte()
				val json = JSONObject()
				json.put("type","VOLUME")
				json.put("value",(volume * 100).toInt())
				sendQueue.put(json)
            }
            "VOLUME_UPDATE" -> {
                updateNotification()
                val fromserver = intent?.getBooleanExtra("fromserver",false) ?: false 
                if (fromserver) {
                    broadcastVolumeUpadte()
                } else { 
					val json = JSONObject()
					json.put("type","VOLUME")
					json.put("value",(volume * 100).toInt())
                    sendQueue.put(json)
                }
            }
        }

        return START_STICKY
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.notify(1,createNotification()) // Перерисовка уведомления
    }

    private fun broadcastStopEvent() {
        sendBroadcast(
            Intent("STOP")
        )
    }

    private fun broadcastVolumeUpadte () {
        // Отправка в приложение изменения громкости
        sendBroadcast(
            Intent("VOLUME_UPDATE").apply { putExtra("volume",volume)}
        )
    }

    private fun createNotification() : Notification {
        val channelID = "audio_channel"
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				channelID,
				"Audio Service",
				NotificationManager.IMPORTANCE_LOW
			)
			getSystemService(NotificationManager::class.java)
				.createNotificationChannel(channel)
		}

        val intent = Intent(this,MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this,AudioService::class.java).apply {action = "STOP"}
        val volumeUpIntent = Intent(this,AudioService::class.java).apply {action="VOLUME_UP"}
        val volumeDownIntent = Intent(this,AudioService::class.java).apply {action="VOLUME_DOWN"}

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val volumeUpPendingIntent = PendingIntent.getService(
            this,
            2,
            volumeUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val volumeDownPendingIntent = PendingIntent.getService(
            this,
            3,
            volumeDownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )        

        val volumePercent = (volume * 100).toInt()

        return NotificationCompat.Builder(this, channelID)
            .setContentTitle("Звук с порта воспроизводиться")
            .setContentText("Громкость: $volumePercent%")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            // .setAutoCancel(true)
            .addAction(android.R.drawable.ic_media_rew,"-10%",volumeDownPendingIntent)
            .addAction(android.R.drawable.ic_media_pause,"Stop",stopPendingIntent)
            .addAction(android.R.drawable.ic_media_ff,"+10%",volumeUpPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder?{
		return null
	}

    private fun stopService() {
        isRunning = false
        isConnect = false 

        audioTrack?.stop()
        audioTrack?.release()
        UDPsocket?.close()
        thread_Audio?.interrupt()

        thread_Audio = null
        UDPsocket = null
        audioTrack = null 
		
		serverSocket?.close()
		socket?.close() 
		thread_Server?.interrupt()

		serverSocket = null 
		socket = null 
		thread_Server = null 
		writer = null 
		reader = null 

        thread_queue?.interrupt()
        thread_queue = null 
    }

	private fun RunServer() {
        Log.d(TAG,"Запуск сервера")
		thread_Server = Thread(::Control, "ControlThread")
		thread_Server?.start()
        
        thread_queue = Thread(::check_queue, "QueueThread")
        thread_queue?.start()
	}

    private fun Control() {
        serverSocket = ServerSocket(5001)
        while (isRunning) {
            try {
                    Log.d(TAG,"Поиск клиент")
                    socket = serverSocket?.accept()
                    Log.d(TAG,"Нашёлся клиент: ${socket?.inetAddress?.hostAddress}")
                    isConnect = true
                    socket?.let {
                        reader = it.getInputStream().bufferedReader()
                        writer = it.getOutputStream().bufferedWriter()
                    }
                    getLoop()
                    isConnect = false 
            } catch (e: Exception) {
                if (!isRunning) {
                    break
                } else {
                    Log.e(TAG,"Ошибка в Control", e)
                }
            } finally {
                socket?.close()
                reader?.close()
                writer?.close()

                socket = null 
                reader = null 
                writer = null 
            }
        }
    }

    private fun getLoop() {
        try {
            while (isRunning) {
                var line = reader?.readLine() ?: break
                handlerCommand(JSONObject(line))
            } 
        } catch (e: Exception){
            Log.e(TAG,"Ошибка в getLoop",e)
            return
        }
    }

    private fun handlerCommand(json: JSONObject) {
        Log.d(TAG,"Пришел пакет: ${json}")
        when (json.getString("type")) {
            "PING" -> {
                val json = JSONObject()
                json.put("type","PONG")
                sendQueue.put(json)
            }
            "VOLUME" -> {
                volume = json.getInt("value").toFloat() / 100f
                startService(Intent(this, AudioService::class.java).apply {
                    action = "VOLUME_UPDATE"
                    putExtra("fromserver",true)
                })
            }
        }
    }

    private fun send(json: JSONObject) {
        Log.d(TAG,"Отправка пакета: ${json}")
        try {
            writer?.let{
                it.write(json.toString())
                it.newLine()
                it.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG,"Ошибка в send",e)
        }
    }

    private fun check_queue() {
        while (isRunning) {
            try {
                val json = sendQueue.take()
                if (isConnect == false) {
                    break
                } else {
                    send(json)
                }
            } catch (_: Exception) {
                if (isConnect == false) {
                    break
                } else {
                    continue
                }
            }
        }
    }

    private fun startAudioReceiver() {
        thread_Audio = Thread{
            try{
                val sampleRate = 44100
                UDPsocket = DatagramSocket(5000)
                val buffer = ByteArray(8192)                
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buffer.size,
                    AudioTrack.MODE_STREAM
                )
                audioTrack?.setVolume(volume)
                val at = audioTrack
                val s = UDPsocket
                if (at != null && s != null) {
                    at.play()
                    while (isRunning){
                        try {
                            at.setVolume(volume)                          
                            val packet = DatagramPacket(buffer,buffer.size)
                            s.receive(packet)                        
                            at.write(packet.data, 0, packet.length)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }
        }
        thread_Audio?.start()
    }
}

class MainActivity : AppCompatActivity() {

    private var count = 0
	private var isRunning = false
    
    private val volumeRecover = object : BroadcastReceiver() {
        override fun onReceive(context: Context?,intent: Intent?) {
            val volume = intent?.getFloatExtra("volume", 1.0f) ?: return 

            val volumeSlider = findViewById<SeekBar>(R.id.VolumeSlider)
            val volumeText = findViewById<TextView>(R.id.VolumeText)

            val currect_volume = (volume * 100).toInt()

            volumeSlider.progress = currect_volume
            volumeText.text = "$currect_volume%"
        }
    }

    private val stopRecover = object : BroadcastReceiver() {
        override fun onReceive(context: Context?,intent: Intent?) {
            isRunning = true // на всякий
            updateUI(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val button_1 = findViewById<Button>(R.id.AudioButton)

        button_1.setOnClickListener{updateUI()}

        val volumeSlider = findViewById<SeekBar>(R.id.VolumeSlider)
        val volumeText = findViewById<TextView>(R.id.VolumeText)

        volumeSlider.progress = 100

        volumeSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?,progress:Int,fromUser:Boolean){
                    volumeText.text = "$progress%"

                    AudioService.volume = progress / 100.0f
                }
                override fun onStartTrackingTouch(seekBar:SeekBar?) {}
                override fun onStopTrackingTouch(seekBar:SeekBar?) {
                    startService(Intent(this@MainActivity, AudioService::class.java).apply {action = "VOLUME_UPDATE"})
                }
            }
        )

        volumeSlider.visibility = View.GONE
        volumeText.visibility = View.GONE

        updateAudioButton(button=button_1,seekBar=volumeSlider,textView=volumeText)

        registerReceiver(volumeRecover,IntentFilter("VOLUME_UPDATE")) // регестрация события
        registerReceiver(stopRecover,IntentFilter("STOP"))
    }

    private fun updateUI(CommunicateService: Boolean = true) {
        val button_1 = findViewById<Button>(R.id.AudioButton)
        val volumeSlider = findViewById<SeekBar>(R.id.VolumeSlider)
        val volumeText = findViewById<TextView>(R.id.VolumeText)

        if (!isRunning) {
            isRunning = true
            if (CommunicateService) {
                startService(Intent(this, AudioService::class.java).apply {
                    action = "START"
                })
            }
            button_1.text = "Stop"
            volumeSlider.visibility = View.VISIBLE
            volumeText.visibility = View.VISIBLE
        } else {
            isRunning = false
            if (CommunicateService) {
                startService(Intent(this,AudioService::class.java).apply {
                    action = "STOP"
                })
            }
            button_1.text = "Start"
            volumeSlider.visibility = View.GONE
            volumeText.visibility = View.GONE
        }
    }

    private fun updateAudioButton(button: Button?,seekBar: SeekBar?,textView: TextView?) {
        if (AudioService.instance != null) {
            button?.text = "Stop"
            seekBar?.let{
                it.visibility = View.VISIBLE
                it.progress = (AudioService.volume * 100).toInt()
            }
            textView?.let{
                it.visibility = View.VISIBLE
                it.text = "${(AudioService.volume * 100).toInt()}%"
            }
            isRunning = true
        } else {
            isRunning = false
        }
    }

    override fun onDestroy() {
        unregisterReceiver(volumeRecover) // Убирает приёмник 
        unregisterReceiver(stopRecover)
        super.onDestroy()
    }
}