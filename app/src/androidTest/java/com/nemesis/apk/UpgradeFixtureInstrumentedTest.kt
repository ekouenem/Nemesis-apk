package com.nemesis.apk

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only on a disposable offline emulator: creates and removes its own fixture. */
@RunWith(AndroidJUnit4::class)
class UpgradeFixtureInstrumentedTest {
 @Test fun prepareOnOldVersion() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("upgrade_fixture")=="prepare")
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val m=Python.getInstance().getModule("mobile_agent")
  val id=JSONObject(m.callAttr("create_session","chat").toString()).getString("id")
  val text="Ancienne discussion préservée 😀 "+"texte ".repeat(3000)
  m.callAttr("_message",id,"assistant",text,12345)
  val url="https://chat.deepseek.com/a/chat/s/testabc123"
  android.database.sqlite.SQLiteDatabase.openDatabase(ctx.filesDir.resolve("nemesis.sqlite3").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("UPDATE sessions SET conversation_url=?, uncertain=1 WHERE id=?",arrayOf(url,id)) }
  ctx.getSharedPreferences("beta8_upgrade_fixture",Context.MODE_PRIVATE).edit().putString("id",id).putString("text",text).commit()
  ctx.filesDir.resolve("beta8-upgrade-fixture.txt").writeText("Fichier privé conservé")
  a.finish()
 }
 @Test fun verifyAfterUpgrade() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("upgrade_fixture")=="verify")
  val inst=InstrumentationRegistry.getInstrumentation(); val ctx=inst.targetContext
  val a=inst.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  inst.waitForIdleSync(); val m=Python.getInstance().getModule("mobile_agent"); val prefs=ctx.getSharedPreferences("beta8_upgrade_fixture",Context.MODE_PRIVATE)
  val id=prefs.getString("id",null) ?: throw AssertionError("Fixture d’ancienne version manquante")
  try {
   val session=JSONObject(m.callAttr("get_session",id).toString())
   assertEquals("https://chat.deepseek.com/a/chat/s/testabc123",session.getString("conversation_url"))
   assertEquals(1,session.getInt("uncertain")); assertEquals(prefs.getString("text",null),session.getJSONArray("messages").getJSONObject(0).getString("text"))
   assertEquals("Fichier privé conservé",ctx.filesDir.resolve("beta8-upgrade-fixture.txt").readText())
   val show=MainActivity::class.java.getDeclaredMethod("showChat",String::class.java).apply { isAccessible=true }
   inst.runOnMainSync { show.invoke(a,id) }; awaitChat(a)
  } finally { m.callAttr("delete_session",id); ctx.filesDir.resolve("beta8-upgrade-fixture.txt").delete(); prefs.edit().clear().commit(); a.finish() }
 }
}
