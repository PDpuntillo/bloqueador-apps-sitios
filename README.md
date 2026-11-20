# Bloqueador de apps y sitios web

Programa de **autocontrol** para bloquear aplicaciones y sitios web durante sesiones de
concentración u horarios programados. Hay una versión para **Windows** y otra para **Android**.
Cada dispositivo tiene sus propias listas (no se sincronizan).

---

## Windows

### Requisitos

- Windows 10 u 11.
- Python 3.9 o más nuevo (el instalador oficial ya incluye tkinter). No hace falta instalar nada más.

### Cómo abrirlo

1. Doble clic en `windows/bloqueador.pyw`.
2. Acepta la ventana de permisos de administrador. Hace falta para modificar el archivo
   `hosts` de Windows y para cerrar programas.
3. Recomendado: en **Ajustes**, activa *"Abrir el Bloqueador automáticamente al iniciar
   Windows"*. Así los horarios se cumplen aunque reinicies la PC, y no vuelve a pedir permisos.

### Uso

| Pestaña | Qué haces |
|---|---|
| **Sitios web** | Escribe una dirección (`youtube.com`) o usa los botones de *Agregar rápido*. |
| **Aplicaciones** | Despliega la lista de programas abiertos, escribe el nombre (`discord.exe`) o usa *Examinar…*. |
| **Horarios** | Elige días y horas. Si la hora final es menor que la inicial, termina al día siguiente (22:00 → 07:00). Si las dos horas son iguales, dura 24 h (`00:00 → 00:00` bloquea el día completo). Los horarios tienen que dejar al menos un rato libre en la semana: el programa rechaza los que harían que el bloqueo no terminara nunca. Si un horario termina justo cuando empieza otro, el fin que se muestra es el del último de la cadena. |
| **Límites diarios** | Un máximo de uso por día para cada sitio o programa (por ejemplo, `instagram.com`, 30 minutos). La lista muestra cuánto usaste hoy y cuánto te queda. Ver *Límites diarios* más abajo. |
| **Ajustes** | Inicio automático con Windows, cómo funciona y *Salir del Bloqueador*. |

Arriba de las pestañas está el recuadro **Sesión de concentración**: bloquea todo durante las
horas y minutos que elijas (de 1 minuto a 24 horas), a partir de ahora.

**Durante un bloqueo** puedes agregar sitios, apps u horarios, pero **no** puedes quitarlos,
detener la sesión, desactivar el inicio automático ni cerrar el programa (la X solo lo minimiza).
Única excepción: si una configuración de una versión anterior tiene horarios que cubren toda la
semana (un bloqueo "sin fin"), sí puedes quitar horarios para que el bloqueo pueda terminar.

### Límites diarios

- Cuenta el tiempo que el programa o el sitio está **en primer plano** (la ventana activa). El
  contador vuelve a cero cada día a las 00:00 y no cuenta mientras la PC está suspendida.
- Los navegadores no dicen qué dirección muestran, así que un sitio se reconoce por el **título
  de la pestaña activa**, que casi siempre lleva el nombre del sitio (`Inicio • Instagram`,
  `Video - YouTube`, `Inicio / X`). Una página cuyo título no lo menciona no cuenta, y una
  página de otro sitio que lo menciona sí.
- Cuando quedan 5 minutos aparece un aviso. Al acabarse el tiempo, el programa se cierra y el
  sitio se bloquea en el archivo hosts **hasta las 00:00**. Si su pestaña está a la vista, se
  cierra (el Bloqueador pulsa Ctrl+W).
- Agregar un límite o bajarlo se puede siempre. **Quitarlo o aumentarlo, no** durante un bloqueo
  ni si hoy ya se acabó su tiempo. Mientras haya un límite agotado, el programa tampoco se puede
  cerrar (la X lo minimiza).
- El tiempo de hoy se guarda en `C:\ProgramData\Bloqueador\uso.json`.

### Cómo funciona

- **Sitios:** el Bloqueador escribe en `C:\Windows\System32\drivers\etc\hosts` líneas como
  `0.0.0.0 youtube.com` (también `www.`, `m.` y sus direcciones del traductor de Google, como
  `www-youtube-com.translate.goog`), entre dos marcas para no tocar el resto del archivo.
  Funciona en todos los navegadores. Se revisa cada segundo: si alguien borra esas líneas,
  vuelven a aparecer.
- **Aplicaciones:** cada segundo revisa los programas abiertos y cierra los bloqueados. Muestra
  un aviso en la esquina de la pantalla.
- **Configuración:** `C:\ProgramData\Bloqueador\config.json`. Los errores se guardan en
  `error.log` en la misma carpeta.

### Límites conocidos

- El archivo hosts no admite comodines: se bloquean el dominio, `www.` y `m.`. Otros subdominios
  (por ejemplo `music.youtube.com`) se agregan aparte.
- Una VPN, un proxy o Tor se saltan el archivo hosts.
- Si cierras el Bloqueador a la fuerza desde el Administrador de tareas, los sitios **siguen**
  bloqueados, pero las apps no, hasta que se vuelva a abrir (al iniciar sesión, si activaste
  el inicio automático).
- Cambiar la hora de Windows puede terminar un bloqueo antes de tiempo.
- Una página que ya estaba abierta puede tardar un poco en bloquearse: recárgala.
- Algunos antivirus avisan cuando un programa modifica el archivo hosts.

### Desbloqueo de emergencia

Si borras el programa durante un bloqueo, los sitios seguirán bloqueados. Para quitarlos a mano:

1. Abre el Bloc de notas **como administrador** y abre `C:\Windows\System32\drivers\etc\hosts`.
2. Borra todo lo que hay entre `# >>> Bloqueador (no editar este bloque) >>>` y
   `# <<< Bloqueador <<<` (incluidas esas dos líneas), y guarda.
3. Para quitar el inicio automático: abre el *Programador de tareas* y borra la tarea
   *"Bloqueador de apps y sitios web"*.

### Pruebas

Desde la carpeta `windows`:

```bash
python -m unittest discover -s tests -v
```

Las pruebas usan archivos temporales y nunca tocan el archivo hosts real.
Para abrir la ventana sin permisos de administrador y con un archivo hosts falso, define antes
estas variables de entorno: `BLOQUEADOR_SIN_ADMIN=1`, `BLOQUEADOR_HOSTS=<ruta a un archivo>` y
`BLOQUEADOR_DATOS=<carpeta>`.

### Estructura

```
windows/
  bloqueador.pyw        punto de entrada: pide permisos y abre la ventana
  nucleo/
    config.py           listas, horarios, límites, sesión y guardado en JSON
    hosts.py            bloqueo de sitios con el archivo hosts
    procesos.py         programas abiertos, ventana activa y cierre (ctypes, sin librerías externas)
    uso.py              tiempo de uso de hoy de lo que tiene límite diario
    motor.py            cada segundo decide si hay bloqueo y lo aplica
    autoinicio.py       tarea del Programador de tareas para abrir al iniciar sesión
    interfaz.py         la ventana (tkinter)
  tests/test_nucleo.py
```

---

## Android

App nativa en **Kotlin** (Jetpack Compose), para Android 8.0 o más nuevo. No necesita root.
Tiene sus propias listas, sesiones y horarios, con las mismas reglas que en Windows.

### Instalarla en el teléfono

El APK ya compilado, `Bloqueador.apk`, está en la sección
[**Releases**](https://github.com/PDpuntillo/bloqueador-apps-sitios/releases) del repositorio.
También puedes compilarlo tú (ver *Compilar y probar*).

1. Descárgalo desde el teléfono, o pásalo desde la PC por cable USB o por Google Drive.
2. Ábrelo desde el teléfono (por ejemplo, desde la app *Archivos*). La primera vez, Android pide
   permitir *"Instalar apps desconocidas"* para la app desde la que lo abres: actívalo y vuelve.
3. Si Play Protect avisa que no conoce la app, toca *Más detalles > Instalar de todos modos*.
   Es normal en una app que no viene de Google Play.

Es un APK de depuración, firmado con la clave de esta PC. Para actualizarlo más adelante, instala
encima otro compilado en la misma PC. Si lo compilas en otra, primero quita la Protección contra
desinstalación (pestaña Ajustes > *Quitar protección*, fuera de un bloqueo) y desinstala el
anterior: perderás las listas guardadas.

### Primeros pasos

Al abrirla, la pestaña **Inicio** muestra qué falta activar:

1. **Bloqueo de apps:** en Ajustes > Accesibilidad, activa *"Bloqueador: bloqueo de apps"*. En
   Android 13 o más nuevo, si el interruptor aparece gris: Ajustes > Apps > Bloqueador > menú ⋮ >
   *"Permitir ajustes restringidos"*.
2. **Bloqueo de sitios:** toca *Activar* y acepta la conexión VPN. Si usas otra VPN, se
   desconecta: Android solo permite una a la vez.
3. Recomendado: en Ajustes de VPN, activa *"VPN siempre activada"* para el Bloqueador. **No**
   actives *"Bloquear conexiones sin VPN"*: te dejaría sin internet.
4. Recomendado: el **modo estricto** (pestaña Ajustes). Durante un bloqueo también bloquea Ajustes,
   los gestores de apps del fabricante, el instalador de apps y las tiendas, así que desde ahí no
   se puede desinstalar el Bloqueador. En **Android 14 o más nuevo**, da también **«Acceso a datos
   de uso»** (Inicio lo pide con el botón *Dar acceso*): Android oculta algunas pantallas de Ajustes
   al bloqueo de apps, y sin ese acceso el modo estricto no puede taparlas.
5. Opcional: la **Protección contra desinstalación** (pestaña Ajustes > *Activar*). Algunos
   launchers, como el de Xiaomi, desinstalan apps por su cuenta, sin pasar por Ajustes; con la
   protección activa, Android tampoco los deja desinstalar el Bloqueador. Ver más abajo.
6. En Xiaomi, Redmi, POCO, Huawei, Honor, Oppo, Realme, OnePlus y Vivo, sigue también los pasos
   que muestra Inicio: fijar el Bloqueador en Recientes, inicio automático, batería *"Sin
   restricciones"* y, en Xiaomi, *"Mostrar ventanas emergentes en segundo plano"*. En otras
   marcas con ahorro de batería agresivo, busca esos mismos ajustes a mano.

### Uso

| Pestaña | Qué haces |
|---|---|
| **Inicio** | Ves si hay un bloqueo y hasta cuándo, empiezas una **sesión de concentración** (con los botones rápidos o escribiendo horas y minutos, hasta 24 horas) y revisas qué falta activar. |
| **Apps** | Marcas las apps instaladas que quieres bloquear. El teléfono, los ajustes y el propio Bloqueador no se pueden bloquear. |
| **Sitios** | Escribes una dirección (`youtube.com`) o usas *Agregar rápido*, que también agrega los dominios de videos e imágenes de cada servicio y ofrece bloquear su app. |
| **Límites** | Un máximo de uso por día para cada app o sitio (por ejemplo, Instagram, 30 minutos). Cada límite muestra cuánto usaste hoy. Tocar uno lo carga en el formulario para cambiarlo. |
| **Horarios** | Igual que en Windows: si la hora final es menor, termina al día siguiente, y si las dos son iguales, dura 24 h. No se aceptan horarios que cubran la semana entera. Si un horario empieza justo cuando termina otro (o una sesión), el bloqueo sigue sin pausa y la app muestra el fin real. |
| **Ajustes** | Modo estricto, Protección contra desinstalación y cómo funciona cada cosa (también *"Qué no cubre"*). |

### Cómo funciona

- **Apps:** un *servicio de accesibilidad* detecta qué app pasa a primer plano y, si está
  bloqueada, la tapa con la pantalla de bloqueo. También cierra las ventanas de imagen en imagen
  (PiP) de esas apps. Solo mira el nombre de cada app: no lee lo que hay en pantalla (salvo la
  barra de direcciones del navegador si pusiste límite a algún sitio; ver *Límites diarios*).
- **Sitios web:** una *VPN local* (`VpnService`) que solo recibe las consultas DNS. Si el sitio
  está en la lista y hay un bloqueo, responde "no existe"; si no, la reenvía al DNS de tu red.
  El resto del tráfico no pasa por la VPN, y nada se envía a servidores del Bloqueador. Si la red
  no informa sus servidores DNS, las consultas van a 1.1.1.1 (Cloudflare) y 8.8.8.8 (Google).
  Los sitios abiertos
  con el traductor de Google (por ejemplo, `www-reddit-com.translate.goog`) también se bloquean.
- **Durante un bloqueo** puedes agregar sitios, apps y horarios, o extender la sesión, pero no
  quitar nada, detener el bloqueo, apagar el modo estricto ni quitar la Protección contra
  desinstalación.
- **Límites diarios:** el servicio de accesibilidad cuenta el tiempo que cada app con límite
  está en pantalla (no cuenta con la pantalla apagada). Para los sitios lee la **barra de
  direcciones** de los navegadores conocidos (Chrome, Samsung Internet, Firefox, Edge, Brave,
  Opera, Vivaldi, DuckDuckGo y algunos más), solo si hay algún sitio con límite, y no guarda la
  dirección. Cuando quedan 5 minutos aparece un aviso; al acabarse, la app o el sitio se tapa
  con la pantalla de bloqueo hasta las 00:00. El filtro de sitios también deja de resolverlo.
  Si un sitio se acaba, la pantalla de bloqueo ofrece *Abrir otra página* en el mismo
  navegador. Agregar o bajar un límite se puede siempre; quitarlo o aumentarlo, no durante un
  bloqueo ni si hoy ya se acabó su tiempo.

### Protección contra desinstalación

Es opcional y se activa en la pestaña **Ajustes**. Usa un *administrador de dispositivo* sin
ninguna política: no puede bloquear el teléfono, borrar datos ni ver lo que haces. Mientras está
activa, Android no deja desinstalar el Bloqueador, tampoco desde los launchers que desinstalan por
su cuenta (como el de Xiaomi).

- **Para quitarla:** pestaña Ajustes > *Quitar protección*, fuera de un bloqueo. Después ya puedes
  desinstalar el Bloqueador como cualquier app.
- **Desde Ajustes del teléfono:** busca *"administración del dispositivo"* o *"administradores de
  dispositivos"* y desactiva el Bloqueador. Con el modo estricto, durante un bloqueo esa pantalla
  no se abre; sin el modo estricto, sí.
- **En una emergencia:** reinicia en modo seguro. Ahí el Bloqueador no funciona y puedes quitar la
  protección desde Ajustes.

### Límites conocidos

Algunas cosas no se pueden resolver desde una app normal:

- **Forzar la detención** del Bloqueador, o un limpiador del teléfono (*"Limpiar todo"* en
  Recientes de Xiaomi, Huawei, Oppo...), apaga el bloqueo de apps hasta que lo vuelvas a activar
  a mano. Android no avisa. En esos teléfonos, fija el Bloqueador en Recientes con el candado,
  permite su inicio automático y deja su batería *"Sin restricciones"*.
- **Xiaomi (MIUI / HyperOS):** sin el permiso *"Mostrar ventanas emergentes en segundo plano"*, la
  pantalla de bloqueo no aparece: la app bloqueada se ve un instante y el teléfono vuelve al
  inicio. Inicio avisa y tiene un botón a ese permiso.
- **Modo seguro:** reiniciar en modo seguro apaga el Bloqueador. Es la salida de emergencia, y ahí
  también se puede quitar la Protección contra desinstalación.
- **ADB:** si activaste antes la depuración USB o inalámbrica, desde ella se puede detener el
  Bloqueador y, si no tiene la Protección contra desinstalación, desinstalarlo. El APK de
  depuración, además, deja borrar sus datos con ADB.
- **Launchers que desinstalan por su cuenta** (como el de Xiaomi): sin la Protección contra
  desinstalación, pueden desinstalar el Bloqueador incluso durante un bloqueo con modo estricto.
  Con la protección activa, Android lo impide. No se probó en todos los launchers: alguno podría
  ofrecer quitar la protección desde su propio menú.
- **Pantalla dividida y ventanas flotantes:** si pones una app bloqueada en pantalla dividida, en
  una vista emergente (Samsung) o en una ventana flotante (Xiaomi), sigue a la vista y se puede
  usar en esa parte de la pantalla. La pantalla de bloqueo no se vuelve a abrir una y otra vez
  encima, porque solo parpadearía. Las ventanas de imagen en imagen (PiP) de las apps de tu lista
  sí se cierran, salvo que Android no lo permita.
- **Audio:** al tapar una app de tu lista, la pantalla de bloqueo pausa lo que esté sonando, aunque
  sea de una app permitida. Android no dice qué app suena.
- **"Fijar apps":** con una app fijada en pantalla, el Bloqueador no puede taparla. Desactiva
  *"Fijar apps"* en Ajustes si no lo usas.
- **Atajo de accesibilidad** (Android 8 a 10): si asignas el Bloqueador al atajo (mantener las
  teclas de volumen), ese atajo lo apaga sin pasar por Ajustes. No lo asignes. En Android 11 o
  más nuevo, el servicio pide un botón de accesibilidad que no hace nada justamente para evitarlo.
- **Una sola VPN a la vez:** activar el filtro de sitios desconecta otra VPN (por ejemplo, la del
  trabajo) y conectar otra apaga el filtro. Durante un bloqueo, si el bloqueo de apps está activo,
  el Bloqueador intenta volver a encenderlo en unos segundos. No puede si la otra VPN está
  configurada como *"VPN siempre activada"*.
- **El filtro de sitios solo ve el DNS del teléfono.** No cubre:
  - navegadores con un *DNS seguro* personalizado (por ejemplo, con una dirección IP) o con VPN
    integrada (como Opera). Para bloquearlos del todo, agrega el navegador a Apps;
  - apps que usan su propio DNS o direcciones fijas;
  - las pestañas que ya estaban abiertas, que pueden seguir funcionando un rato: ciérralas o
    cierra el navegador al empezar el bloqueo;
  - los sitios abiertos con otros proxies o archivos web, como archive.org o la caché de un
    buscador (el traductor de Google sí se cubre).
- **"DNS seguro" con un proveedor conocido:** si tu navegador lo usa, durante un bloqueo puede que
  no cargue ninguna página. Desactívalo o deja que use el DNS del teléfono.
- **"DNS privado"** fijado a un proveedor en Ajustes de red puede saltarse el filtro. Inicio avisa:
  cámbialo a *Automático* o *Desactivado*.
- **Otros perfiles:** el perfil de trabajo, el Espacio privado, la Carpeta segura de Samsung, las
  apps duplicadas y otros usuarios del teléfono no pasan por el filtro de sitios.
- **Sin internet para el filtro:** si el filtro no consigue salir a internet pero el resto del
  teléfono sí (por ejemplo, porque le quitaste a Bloqueador el permiso de datos), fuera de un
  bloqueo se pausa solo para no dejarte sin internet, e Inicio lo avisa. Mientras está pausado,
  las apps usan directo el DNS de tu red y no se bloquea ningún sitio. Se pausa solo por un
  problema de Bloqueador: si toda la red está caída (sin señal, una Wi-Fi sin internet), no.
  - Durante un bloqueo no se pausa: si el filtro sigue sin red, puede que no cargue ningún sitio
    hasta que lo arregles. Al empezar un bloqueo, el filtro vuelve solo.
  - Cuando lo arregles, abre el Bloqueador o toca *Activar* en Inicio para que vuelva al
    instante. Si no, lo comprueba solo, cada vez más espaciado: puede tardar varios minutos.
  - Al pausarse y al volver, Android ve una VPN nueva y algunas apps se reconectan.
  - Con *"Bloquear conexiones sin VPN"* activado no se pausa, porque no serviría.
- **Modo estricto:** también bloquea las compras dentro de otras apps (pasan por Google Play).
  Siguen funcionando dar permisos, vincular un Bluetooth y escribir la contraseña de una Wi-Fi.
  Algunas pantallas de cada fabricante pueden quedar bloqueadas de más. En Android 14 o más
  nuevo, sin *"Acceso a datos de uso"*, una pantalla de Ajustes que ya estaba abierta (por
  ejemplo, Opciones de desarrollador) se puede volver a abrir durante el bloqueo.
- Las **notificaciones y los widgets** de las apps bloqueadas siguen apareciendo.
- **Cambiar la hora** del teléfono puede terminar un bloqueo antes de tiempo (con el modo estricto,
  Ajustes no se abre durante el bloqueo). Cambiar solo la zona horaria no acorta una sesión.

### Desbloqueo de emergencia

- **Sin modo estricto:** la app no te deja quitar nada durante un bloqueo, pero Android sí. En
  Ajustes > Accesibilidad, apaga *"Bloqueador: bloqueo de apps"*, y en Ajustes de VPN desconecta
  el Bloqueador.
- **Con modo estricto, durante un bloqueo,** Ajustes no se abre. Espera a que termine o reinicia
  en **modo seguro**:
  - En la mayoría de los teléfonos: mantén el botón de encendido, después mantén presionado
    *Apagar* (o *Reiniciar*) y acepta *Modo seguro*.
  - En Samsung: apágalo, enciéndelo y mantén *Bajar volumen* cuando aparezca el logo.

  En modo seguro el Bloqueador no funciona. Desde Ajustes puedes quitar la Protección contra
  desinstalación (si la activaste) y desactivar o desinstalar el Bloqueador. Después, reinicia
  normalmente.

### Compilar y probar

Hace falta el JDK 17 y el Android SDK 35. Android Studio trae los dos. En esta PC quedaron en
`%LOCALAPPDATA%\Android\jdk-17` y `%LOCALAPPDATA%\Android\Sdk`, y `android/local.properties`
apunta al SDK. Desde una terminal en la carpeta `android`:

```bat
set JAVA_HOME=%LOCALAPPDATA%\Android\jdk-17
gradlew.bat assembleDebug
gradlew.bat lintDebug testDebugUnitTest
```

El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`.

Si la ruta de la carpeta del proyecto es muy larga, la compilación falla con errores de `aapt2`.
Para evitarlo, asígnale una letra de unidad: `subst B: "<carpeta del proyecto>"`, compila desde
`B:\android` y al terminar quita la unidad con `subst B: /d`.

Las 64 pruebas corren en la PC, no en el teléfono. Cubren las reglas: horarios, estado del
bloqueo, guardado de la configuración (JSON), dominios, textos de fechas y duraciones, y la
lectura y armado de las consultas DNS.

Además se probó en un **Samsung Galaxy S23 con Android 16 (One UI 8.5)**, por USB:

- el bloqueo de sitios en Chrome y en el sistema (también el traductor de Google y el DNS seguro);
- la pantalla de bloqueo sobre una app;
- el modo estricto: Ajustes, Información de la app, Accesibilidad, VPN, Opciones de desarrollador
  y Play Store;
- la vuelta a la normalidad al terminar el bloqueo;
- el arranque del filtro después de actualizar la app.

Queda por probar en otros teléfonos, sobre todo Xiaomi, Huawei y Oppo, y en Android 8 a 13.

### Estructura

```
android/app/src/main/java/com/bloqueador/android/
  dominio/     reglas: configuración, horarios, estado del bloqueo, dominios y paquetes
  datos/       guardado de la configuración y del tiempo de uso de hoy
  bloqueo/     servicio de accesibilidad, VPN (filtro DNS), pantalla de bloqueo, arranque,
               protección contra desinstalación
  ui/          pantallas (Inicio, Apps, Sitios, Límites, Horarios, Ajustes)
android/app/src/test/...      pruebas de las reglas y del análisis DNS
```
