package com.bloqueador.android

import android.app.Application
import com.bloqueador.android.datos.Almacen

class BloqueadorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Almacen.iniciar(this)
    }
}
