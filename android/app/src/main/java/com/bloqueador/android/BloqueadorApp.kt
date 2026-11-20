package com.bloqueador.android

import android.app.Application
import com.bloqueador.android.datos.Almacen
import com.bloqueador.android.datos.AlmacenUso

class BloqueadorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Almacen.iniciar(this)
        AlmacenUso.iniciar(this)
    }
}
