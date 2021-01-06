package com.xeio.recruittagscanner.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.*
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.xeio.recruittagscanner.Globals
import com.xeio.recruittagscanner.managers.DataManager
import com.xeio.recruittagscanner.managers.RecruitmentManager
import java.io.File
import java.io.IOException

class ScreenshotWatcherService : Service() {
    var contentObserver: ContentObserver? = null

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        Log.i(Globals.TAG, "ScreenshotWatcherService create")
        super.onCreate()

        createNotificationChannel()
        enableHttpCache()

        //Trigger static constructor for data, starts the async data fetches
        //Maybe find a better way to do this (while avoiding network on main thread?)
        DataManager.allTags
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(Globals.TAG, "ScreenshotWatcherService start")

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if(uri == null) return
                val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME,
                                            MediaStore.Images.Media.RELATIVE_PATH)
                val query = this@ScreenshotWatcherService.contentResolver.query(uri, projection, null, null, null)
                query?.let{ cursor ->
                    val nameIndex = cursor .getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val pathIndex = cursor .getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    while(cursor.moveToNext()){
                        val name = cursor.getString(nameIndex)
                        val path = cursor.getString(pathIndex)
                        if(path.contains("Screenshot", true) || name.contains("Screenshot", true)){
                            val inputImage = InputImage.fromFilePath(this@ScreenshotWatcherService, uri)
                            val ocrTask = TextRecognition.getClient().process(inputImage)
                            ocrTask.addOnSuccessListener { result ->
                                RecruitmentManager.checkRecruitment(this@ScreenshotWatcherService, result.text, uri)
                            }
                        }
                    }
                }
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.INTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(Globals.TAG, "ScreenshotWatcherService destroy")
        super.onDestroy()

        contentObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
    }

    private fun enableHttpCache(){
        try {
            val httpCacheDir = File(cacheDir, "http")
            val httpCacheSize = 50 * 1024 * 1024.toLong()
            HttpResponseCache.install(httpCacheDir, httpCacheSize)
        } catch (e: IOException) {
            Log.i(Globals.TAG, "HTTP response cache installation failed: $e")
        }
    }

    private fun createNotificationChannel(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mChannel = NotificationChannel(Globals.RECRUIT_CHANNEL_ID, "Recruit Alerts", NotificationManager.IMPORTANCE_HIGH)
            mChannel.description =  "Alerts for recruitment"

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(mChannel)
        }
    }
}