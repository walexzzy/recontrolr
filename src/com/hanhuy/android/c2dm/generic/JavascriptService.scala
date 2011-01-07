package com.hanhuy.android.c2dm.generic

import android.app.IntentService

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.util.Log

import java.io.{File, FileReader, InputStreamReader}

import org.mozilla.javascript.{Context => JSContext}
import org.mozilla.javascript.{Function => JSFunction}
import org.mozilla.javascript.{Script, Scriptable, ScriptableObject, UniqueTag}

import org.json.JSONArray
import org.json.JSONObject

import Conversions._

class JavascriptService extends IntentService("JavascriptService") {
    setIntentRedelivery(true)

    lazy val jsonScript = {
        var script: Script = null
        _usingJS((c: JSContext) => {
            val r = new InputStreamReader(
                    getResources().openRawResource(R.raw.json2))
            usingIO(r, () => {
                script = c.compileReader(r, "json2.js", 1, null)
            })
        })
        script
    }

    lazy val parentScope = {
        var scope: Scriptable = null
        _usingJS((c: JSContext) => {
            val s = c.initStandardObjects()
            jsonScript.exec(c, s)
            ScriptableObject.putConstProperty(s, "context", this)
            scope = s
        })
        scope
    }

    private type Closeable = { def close(): Unit }
    private def usingIO[A](io: Closeable, f: () => A) {
        try {
            f()
        } finally {
            io.close()
        }
    }
    private def _usingJS[A](f: (JSContext) => A) {
        val ctx = JSContext.enter()
        ctx.setLanguageVersion(170)
        try {
            f(ctx)
        } finally {
            JSContext.exit()
        }
    }

    private def usingJS(f: (JSContext, Scriptable) => Object) : String = {
        var string: String = null
        _usingJS((c: JSContext) => {
            val scope = c.newObject(parentScope)
            scope.setPrototype(parentScope)
            scope.setParentScope(null)
            var result = f(c, scope)
            ScriptableObject.putProperty(scope, "result", result)
            if (result != null)
                result = c.evaluateString(scope,
                        "JSON.stringify(result)", "json", 1, null)
            if (result != null) {
                string = JSContext.toString(result)
            }
        })
        string
    }

    override def onHandleIntent(i: Intent) {
        val replyTo = i.getStringExtra(C.PARAM_REPLYTO)
        val id      = i.getStringExtra(C.PARAM_ID)

        val start = SystemClock.elapsedRealtime()
        val o = new JSONObject()
        o.put("success", true)
        val js = i.getStringExtra(C.PARAM_TARGET)
        try {
            if (js == null) {
                Log.e(C.TAG, "JS target not specified")
                o.put("success", false)
                o.put("error", "No JS target set")
            } else {
                val file = new File(js)
                if (js.toLowerCase().endsWith(".js") && file.isFile()) {
                    val r = new FileReader(file)
                    usingIO(r, () => {
                        val result = usingJS((c: JSContext, s: Scriptable) => {
                            c.evaluateReader(s, r, js, 1, null)
                        })
                        Log.i(C.TAG, "JS result: " + result)
                        if (result != null) {
                            if (result.trim().startsWith("{")) // } dumb eclipse
                                o.put("result", new JSONObject(result))
                            else if (result.trim().startsWith("["))
                                o.put("result", new JSONArray(result))
                            else
                                o.put("result", result)
                        }
                    })
                } else {
                    if (!file.exists())
                        o.put("error", "Target does not exist: " + js)
                    else
                        o.put("error", "Target is not a js file: " + js)
                    o.put("success", false)
                }
            }
        } catch {
            case e: Exception => {
                Log.e(C.TAG, "Failed executing script", e)
                o.put("error", e.getMessage())
                o.put("success", false)
            }
        } finally {
            if (i.hasExtra(C.PARAM_DELETE) && js != null)
                new File(js).delete()
        }
        o.put("time", SystemClock.elapsedRealtime() - start)
        RecontrolrRegistrar.respond(replyTo, id, o.toString())
    }
}
