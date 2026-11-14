package com.nemesis.apk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.nemap.android.EmbeddedRuntime
import java.io.File

/** Same Python configuration for foreground sends and scheduled background turns. */
object AgentRuntimeBootstrap {
    @Synchronized fun initialize(context:Context){
        val app=context.applicationContext
        if(!Python.isStarted()) Python.start(AndroidPlatform(app))
        val module=Python.getInstance().getModule("mobile_agent")
        if(module.callAttr("runtime_configured").toString().equals("true",true)) return
        SshBridge.init(app);GoogleWorkspaceBridge.init(app);McpSecretBridge.init(app);AndroidComputerBridge.init(app)
        EmbeddedRuntime.start(app)
        val granted=Build.VERSION.SDK_INT<23 || app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
        val shared=if(granted) sharedDirectory() else ""
        val manager=app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val dns=manager.activeNetwork?.let{network->manager.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull{it.hostAddress?.substringBefore('%')}.joinToString(",")}.orEmpty()
        module.callAttr("configure",app.filesDir.absolutePath,EmbeddedRuntime.port(),EmbeddedRuntime.token(),shared,app.applicationInfo.nativeLibraryDir,Build.SUPPORTED_ABIS.first(),dns)
        try{
            val skills=File(module.callAttr("skills_root").toString())
            copyAssets(app,"skills",skills)
        }catch(e:Exception){android.util.Log.w("NEMESIS-AUTO","Skills indisponibles pour l’automatisation",e)}
        AutomationScheduler.init(app)
    }
    private fun sharedDirectory():String{
        val storage=Environment.getExternalStorageDirectory();val old=File(storage,"NEMESIS");val target=File(storage,"Nemesis")
        if(old.exists()&&!target.exists())old.renameTo(target)
        target.mkdirs();return target.absolutePath
    }
    private fun copyAssets(context:Context,assetPath:String,target:File){
        val children=context.assets.list(assetPath)?: emptyArray()
        if(children.isEmpty()){
            if(!target.exists()){target.parentFile?.mkdirs();context.assets.open(assetPath).use{input->target.outputStream().use{input.copyTo(it)}}}
        }else{target.mkdirs();children.forEach{copyAssets(context,"$assetPath/$it",File(target,it))}}
    }
}
