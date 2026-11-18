"""Pruebas del núcleo. Desde la carpeta windows:  python -m unittest -v

No tocan el archivo hosts real: usan archivos temporales.
"""
import os
import random
import subprocess
import sys
import tempfile
import types
import unittest
from datetime import datetime, timedelta
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from nucleo import hosts, procesos  # noqa: E402
from nucleo.config import Almacen, Config, Estado, Horario, normalizar_hora  # noqa: E402
from nucleo.motor import Motor  # noqa: E402

try:
    from nucleo import interfaz  # noqa: E402  (necesita tkinter)
except ImportError:
    interfaz = None

LUNES = datetime(2026, 11, 16)
TODOS = list(range(7))


def leer(ruta):
    with open(ruta, "rb") as f:
        return f.read().decode("utf-8")


class TestDominios(unittest.TestCase):
    def test_normaliza(self):
        casos = {
            "youtube.com": "youtube.com",
            "  https://www.YouTube.com/watch?v=abc ": "youtube.com",
            "m.facebook.com/": "m.facebook.com",
            "bbc.co.uk": "bbc.co.uk",
            "reddit.com:443": "reddit.com",
        }
        for entrada, esperado in casos.items():
            with self.subTest(entrada=entrada):
                self.assertEqual(hosts.normalizar_dominio(entrada), esperado)

    def test_dominio_con_enie(self):
        d = hosts.normalizar_dominio("ñandú.com")
        self.assertTrue(d.startswith("xn--") and d.endswith(".com"), d)

    def test_rechaza(self):
        for entrada in ["", "   ", "hola", "no valido.com", "192.168.0.1", "-malo-.com", "http://"]:
            with self.subTest(entrada=entrada):
                self.assertIsNone(hosts.normalizar_dominio(entrada))


class TestArchivoHosts(unittest.TestCase):
    ORIGINAL = "# hosts de ejemplo\r\n127.0.0.1 mi-servidor.local\r\n"

    def test_agregar_y_quitar(self):
        con = hosts.construir(self.ORIGINAL, ["youtube.com"])
        for linea in ["0.0.0.0 youtube.com", "0.0.0.0 www.youtube.com", "0.0.0.0 m.youtube.com",
                      ":: youtube.com", "127.0.0.1 mi-servidor.local"]:
            self.assertIn(linea, con)
        self.assertEqual(hosts.construir(con, ["youtube.com"]), con)   # idempotente
        self.assertEqual(hosts.construir(con, []), self.ORIGINAL)      # vuelve al original

    def test_variantes_del_traductor_de_google(self):
        v = hosts.variantes("mi-sitio.com")
        self.assertIn("mi--sitio-com.translate.goog", v)
        self.assertIn("www-mi--sitio-com.translate.goog", v)
        self.assertIn("m-mi--sitio-com.translate.goog", v)
        self.assertIn("0.0.0.0 www-youtube-com.translate.goog", hosts.construir(self.ORIGINAL, ["youtube.com"]))

    def test_bloque_sin_cierre_no_borra_lineas_ajenas(self):
        roto = self.ORIGINAL + hosts.MARCA_INICIO + "\r\n0.0.0.0 youtube.com\r\n10.0.0.5 nas.local\r\n"
        limpio = hosts.construir(roto, [])
        self.assertIn("10.0.0.5 nas.local", limpio)
        self.assertNotIn("youtube", limpio)

    def test_aplicar_en_archivo(self):
        with tempfile.TemporaryDirectory() as d:
            ruta = os.path.join(d, "hosts")
            with open(ruta, "wb") as f:
                f.write(self.ORIGINAL.encode())
            self.assertFalse(hosts.aplicar([], ruta))             # nada que hacer
            self.assertTrue(hosts.aplicar(["reddit.com"], ruta))
            self.assertFalse(hosts.aplicar(["reddit.com"], ruta))  # ya estaba
            self.assertIn("0.0.0.0 reddit.com", leer(ruta))
            self.assertTrue(hosts.aplicar([], ruta))
            self.assertEqual(leer(ruta), self.ORIGINAL)


class TestHorarios(unittest.TestCase):
    def test_lunes_es_lunes(self):
        self.assertEqual(LUNES.weekday(), 0)

    def test_horario_normal(self):
        h = Horario([0, 1, 2, 3, 4], "09:00", "17:00")
        self.assertEqual(h.fin_actual(LUNES.replace(hour=10)), LUNES.replace(hour=17))
        self.assertIsNone(h.fin_actual(LUNES.replace(hour=8, minute=59)))
        self.assertIsNone(h.fin_actual(LUNES.replace(hour=17)))
        self.assertIsNone(h.fin_actual(LUNES + timedelta(days=5, hours=10)))   # sábado

    def test_cruza_medianoche(self):
        h = Horario([4], "22:00", "07:00")   # viernes por la noche
        viernes = LUNES + timedelta(days=4)
        sabado_7 = viernes + timedelta(days=1, hours=7)
        self.assertEqual(h.fin_actual(viernes.replace(hour=23)), sabado_7)
        self.assertEqual(h.fin_actual(viernes + timedelta(days=1, hours=6)), sabado_7)
        self.assertIsNone(h.fin_actual(viernes.replace(hour=6)))   # el jueves no está marcado

    def test_dia_completo(self):
        h = Horario([5], "00:00", "00:00")
        sabado = LUNES + timedelta(days=5)
        self.assertEqual(h.fin_actual(sabado.replace(hour=15)), sabado + timedelta(days=1))
        self.assertIsNone(h.fin_actual(sabado + timedelta(days=1, hours=1)))
        self.assertEqual(h.describir(), "Sáb  ·  todo el día")

    def test_proximo_inicio(self):
        cfg = Config(horarios=[Horario([0], "09:00", "10:00")])
        self.assertEqual(cfg.proximo_inicio(LUNES.replace(hour=8)), LUNES.replace(hour=9))
        self.assertEqual(cfg.proximo_inicio(LUNES.replace(hour=9, minute=30)),
                         LUNES + timedelta(days=7, hours=9))

    def test_normalizar_hora(self):
        self.assertEqual(normalizar_hora("9:00"), "09:00")
        self.assertEqual(normalizar_hora("23:59"), "23:59")
        for malo in ["24:00", "9", "09:5", "ab:cd", ""]:
            self.assertIsNone(normalizar_hora(malo))


class TestConfig(unittest.TestCase):
    def test_sesion(self):
        ahora = LUNES.replace(hour=12)
        cfg = Config(sesion_fin=(ahora + timedelta(minutes=30)).isoformat())
        e = cfg.estado(ahora)
        self.assertTrue(e.activo)
        self.assertEqual(e.motivo, "sesión")
        self.assertEqual(e.hasta, ahora + timedelta(minutes=30))
        self.assertFalse(cfg.estado(ahora + timedelta(minutes=31)).activo)

    def test_sesion_y_horario_usa_el_final_mas_tardio(self):
        ahora = LUNES.replace(hour=10)
        cfg = Config(sesion_fin=(ahora + timedelta(minutes=30)).isoformat(),
                     horarios=[Horario([0], "09:00", "12:00")])
        e = cfg.estado(ahora)
        self.assertEqual(e.motivo, "sesión y horario")
        self.assertEqual(e.hasta, LUNES.replace(hour=12))

    def test_guardar_y_cargar(self):
        with tempfile.TemporaryDirectory() as d:
            ruta = os.path.join(d, "config.json")
            a = Almacen(ruta)
            a.cfg.sitios = ["youtube.com"]
            a.cfg.apps = ["discord.exe"]
            a.cfg.horarios = [Horario([0, 2], "22:00", "07:00")]
            a.guardar()
            b = Almacen(ruta).cfg
            self.assertEqual((b.sitios, b.apps, b.horarios), (a.cfg.sitios, a.cfg.apps, a.cfg.horarios))

    def test_config_danada(self):
        with tempfile.TemporaryDirectory() as d:
            ruta = os.path.join(d, "config.json")
            with open(ruta, "w") as f:
                f.write("{esto no es json")
            self.assertEqual(Almacen(ruta).cfg, Config())
            self.assertTrue(os.path.exists(ruta + ".danado"))


class TestEncadenado(unittest.TestCase):
    """Estado.hasta es el fin real: si al terminar empieza o sigue otro horario sin pausa, sigue."""

    def test_horarios_seguidos(self):
        cfg = Config(horarios=[Horario([0], "09:00", "12:00"), Horario([0], "12:00", "15:00")])
        for hora in (10, 12):
            e = cfg.estado(LUNES.replace(hour=hora))
            self.assertEqual(e.hasta, LUNES.replace(hour=15))
            self.assertEqual(e.motivo, "horario")
            self.assertFalse(e.permanente)
        self.assertFalse(cfg.estado(LUNES.replace(hour=15)).activo)

    def test_cadena_cruza_la_medianoche(self):
        cfg = Config(horarios=[Horario([0], "22:00", "07:00"), Horario([1], "07:00", "09:00"),
                               Horario([1], "08:00", "10:00")])
        self.assertEqual(cfg.estado(LUNES.replace(hour=23)).hasta, LUNES + timedelta(days=1, hours=10))

    def test_un_minuto_libre_corta_la_cadena(self):
        cfg = Config(horarios=[Horario([0], "09:00", "12:00"), Horario([0], "12:01", "15:00")])
        self.assertEqual(cfg.estado(LUNES.replace(hour=10)).hasta, LUNES.replace(hour=12))

    def test_todo_el_dia_lun_a_vie_dura_hasta_el_sabado(self):
        cfg = Config(horarios=[Horario([0, 1, 2, 3, 4], "00:00", "00:00")])
        e = cfg.estado(LUNES.replace(hour=10))
        self.assertEqual(e.hasta, LUNES + timedelta(days=5))   # sábado 00:00, no "mañana"
        self.assertFalse(e.permanente)

    def test_fin_de_semana_completo_dura_hasta_el_lunes(self):
        cfg = Config(horarios=[Horario([5, 6], "00:00", "00:00")])
        sabado = LUNES + timedelta(days=5)
        self.assertEqual(cfg.estado(sabado.replace(hour=15)).hasta, LUNES + timedelta(days=7))

    def test_24_h_seis_dias(self):
        cfg = Config(horarios=[Horario([0, 1, 2, 3, 4, 5], "08:00", "08:00")])
        self.assertFalse(cfg.horarios_sin_pausa())
        self.assertEqual(cfg.estado(LUNES.replace(hour=9)).hasta, LUNES + timedelta(days=6, hours=8))

    def test_sesion_seguida_de_horario(self):
        cfg = Config(sesion_fin=LUNES.replace(hour=10).isoformat(timespec="seconds"),
                     horarios=[Horario([0], "10:00", "11:00")])
        e = cfg.estado(LUNES.replace(hour=9, minute=30))
        self.assertEqual(e.hasta, LUNES.replace(hour=11))
        self.assertEqual(e.motivo, "sesión y horario")

    def test_sesion_con_hueco_no_se_encadena(self):
        cfg = Config(sesion_fin=LUNES.replace(hour=10).isoformat(timespec="seconds"),
                     horarios=[Horario([0], "10:30", "11:00")])
        e = cfg.estado(LUNES.replace(hour=9, minute=30))
        self.assertEqual(e.hasta, LUNES.replace(hour=10))
        self.assertEqual(e.motivo, "sesión")


class TestSinPausa(unittest.TestCase):
    SIN_PAUSA = {
        "24 h desde las 08:00 todos los días": [Horario(TODOS, "08:00", "08:00")],
        "todo el día todos los días": [Horario(TODOS, "00:00", "00:00")],
        "noche y día que se tocan": [Horario(TODOS, "22:00", "07:00"), Horario(TODOS, "07:00", "22:00")],
        "el domingo sigue en el lunes": [Horario([6], "12:00", "12:00"), Horario([0], "12:00", "00:00"),
                                         Horario([1, 2, 3, 4, 5, 6], "00:00", "00:00")],
    }
    CON_PAUSA = {
        "sin horarios": [],
        "todo el día salvo el domingo": [Horario([0, 1, 2, 3, 4, 5], "00:00", "00:00")],
        "un minuto libre": [Horario(TODOS, "22:00", "07:00"), Horario(TODOS, "07:00", "21:59")],
        "falta la noche del domingo": [Horario(TODOS, "07:00", "22:00"),
                                       Horario([0, 1, 2, 3, 4, 5], "22:00", "07:00")],
        "falta el lunes a la mañana": [Horario([0], "12:00", "00:00"),
                                       Horario([1, 2, 3, 4, 5, 6], "00:00", "00:00")],
    }

    def test_detecta_la_semana_cubierta(self):
        for nombre, horarios in self.SIN_PAUSA.items():
            with self.subTest(nombre):
                self.assertTrue(Config(horarios=horarios).horarios_sin_pausa())
        for nombre, horarios in self.CON_PAUSA.items():
            with self.subTest(nombre):
                self.assertFalse(Config(horarios=horarios).horarios_sin_pausa())

    def test_estado_permanente(self):
        cfg = Config(horarios=self.SIN_PAUSA["noche y día que se tocan"])
        for minutos in range(0, 7 * 24 * 60, 97):
            e = cfg.estado(LUNES + timedelta(minutes=minutos))
            self.assertEqual((e.activo, e.hasta, e.permanente, e.motivo), (True, None, True, "horario"))
        cfg.sesion_fin = (LUNES + timedelta(hours=1)).isoformat(timespec="seconds")
        e = cfg.estado(LUNES)
        self.assertEqual((e.activo, e.hasta, e.permanente, e.motivo), (True, None, True, "sesión y horario"))

    def test_coincide_con_revisar_minuto_a_minuto(self):
        azar = random.Random(7)
        horas = ["00:00", "06:00", "07:00", "12:00", "18:00", "22:00", "23:59"]
        semana = [LUNES + timedelta(minutes=i) for i in range(7 * 24 * 60)]
        permanentes = 0
        for _ in range(30):
            horarios = [Horario(sorted({d for d in TODOS if azar.random() < 0.85}) or [0],
                                azar.choice(horas), azar.choice(horas)) for _ in range(azar.randint(1, 3))]
            cfg = Config(horarios=horarios)
            cubierta = all(any(h.fin_actual(t) for h in horarios) for t in semana)
            self.assertEqual(cfg.horarios_sin_pausa(), cubierta, horarios)
            permanentes += cubierta
            for t in azar.sample(semana, 5):
                e = cfg.estado(t)
                if cubierta:
                    self.assertTrue(e.permanente)
                    continue
                self.assertFalse(e.permanente)
                if e.activo:   # todo minuto hasta el fin está bloqueado y el fin es un minuto libre
                    self.assertGreater(e.hasta, t)
                    self.assertFalse(cfg.estado(e.hasta).activo, (horarios, t))
                    paso = t
                    while paso < e.hasta:
                        self.assertTrue(cfg.estado(paso).activo, (horarios, t, paso))
                        paso += timedelta(minutes=53)
        self.assertGreater(permanentes, 0)   # la prueba también cubre casos sin pausa


class Valor:
    """Imita un tk.StringVar / BooleanVar."""

    def __init__(self, valor):
        self.valor = valor

    def get(self):
        return self.valor


class Reloj:
    """Reemplaza a datetime dentro de la interfaz para controlar la hora."""

    def __init__(self, actual: datetime):
        self.actual = actual

    def now(self):
        return self.actual


@unittest.skipIf(interfaz is None, "tkinter no está disponible")
class TestInterfazHorarios(unittest.TestCase):
    def ventana(self, cfg: Config):
        """Un objeto con lo mínimo que usan los métodos de App, sin abrir ninguna ventana."""
        v = types.SimpleNamespace(almacen=types.SimpleNamespace(cfg=cfg), _pie=mock.Mock(),
                                  _guardar_y_aplicar=mock.Mock(), var_minutos=Valor("15"),
                                  var_dias=[Valor(True)] * 7, var_ini=Valor("09:00"), var_fin=Valor("17:00"))
        for nombre in ("_hay_bloqueo", "_ya_hay_bloqueo_mas_largo"):
            setattr(v, nombre, types.MethodType(getattr(interfaz.App, nombre), v))
        return v

    def test_hasta_texto(self):
        ahora = LUNES.replace(hour=10)
        self.assertEqual(interfaz.hasta_texto(Estado(True, LUNES.replace(hour=15, minute=30), "horario"), ahora),
                         "hasta hoy a las 15:30")
        self.assertEqual(interfaz.hasta_texto(Estado(True, None, "horario", True), ahora), "sin fin")

    def test_rechaza_horario_que_no_deja_pausa(self):
        cfg = Config(sitios=["youtube.com"], horarios=[Horario(TODOS, "22:00", "07:00")])
        v = self.ventana(cfg)
        v.var_ini, v.var_fin = Valor("07:00"), Valor("22:00")
        with mock.patch.object(interfaz.messagebox, "showwarning") as aviso, \
                mock.patch.object(interfaz.messagebox, "askyesno") as pregunta:
            interfaz.App._agregar_horario(v)
        self.assertEqual(aviso.call_args.args[1], "Con este horario el bloqueo no terminaría nunca: "
                                                  "deja al menos un rato libre en la semana.")
        pregunta.assert_not_called()
        v._guardar_y_aplicar.assert_not_called()
        self.assertEqual(cfg.horarios, [Horario(TODOS, "22:00", "07:00")])

    def test_confirmacion_muestra_el_fin_encadenado(self):
        cfg = Config(horarios=[Horario([0], "12:00", "15:00")])
        v = self.ventana(cfg)
        v.var_dias = [Valor(i == 0) for i in range(7)]
        v.var_ini, v.var_fin = Valor("09:00"), Valor("12:00")
        with mock.patch.object(interfaz, "datetime", Reloj(LUNES.replace(hour=10))), \
                mock.patch.object(interfaz.messagebox, "askyesno", return_value=True) as pregunta:
            interfaz.App._agregar_horario(v)
        self.assertIn("hasta hoy a las 15:00", pregunta.call_args.args[1])
        self.assertEqual(len(cfg.horarios), 2)

    def test_avisa_si_alarga_el_bloqueo_aunque_no_este_en_curso(self):
        cfg = Config(horarios=[Horario([0], "09:00", "12:00")])
        v = self.ventana(cfg)
        v.var_dias = [Valor(i == 0) for i in range(7)]
        v.var_ini, v.var_fin = Valor("12:00"), Valor("18:00")
        with mock.patch.object(interfaz, "datetime", Reloj(LUNES.replace(hour=10))), \
                mock.patch.object(interfaz.messagebox, "askyesno", return_value=False) as pregunta:
            interfaz.App._agregar_horario(v)
        self.assertIn("hasta hoy a las 18:00", pregunta.call_args.args[1])
        self.assertEqual(len(cfg.horarios), 1)   # respondió que no

    def test_sesion_calcula_el_fin_al_confirmar(self):
        cfg = Config(sitios=["youtube.com"])
        v = self.ventana(cfg)
        reloj = Reloj(LUNES.replace(hour=10))

        def confirmar_tarde(*_a, **_k):   # el diálogo quedó abierto 20 minutos
            reloj.actual += timedelta(minutes=20)
            return True

        with mock.patch.object(interfaz, "datetime", reloj), \
                mock.patch.object(interfaz.messagebox, "askyesno", side_effect=confirmar_tarde):
            interfaz.App._iniciar_sesion(v)
        self.assertEqual(cfg.sesion_fin, "2026-11-16T10:35:00")   # mismo formato que antes
        v._guardar_y_aplicar.assert_called_once()

    def test_sesion_muestra_el_fin_real_si_sigue_un_horario(self):
        cfg = Config(sitios=["youtube.com"], horarios=[Horario([0], "10:15", "12:00")])
        v = self.ventana(cfg)
        with mock.patch.object(interfaz, "datetime", Reloj(LUNES.replace(hour=10))), \
                mock.patch.object(interfaz.messagebox, "askyesno", return_value=True) as pregunta:
            interfaz.App._iniciar_sesion(v)
        self.assertIn("hasta hoy a las 12:00", pregunta.call_args.args[1])
        self.assertEqual(cfg.sesion_fin, "2026-11-16T10:15:00")

    def test_sesion_con_bloqueo_permanente_dice_sin_fin(self):
        cfg = Config(sitios=["youtube.com"], horarios=[Horario(TODOS, "00:00", "00:00")])
        v = self.ventana(cfg)
        with mock.patch.object(interfaz, "datetime", Reloj(LUNES.replace(hour=10))), \
                mock.patch.object(interfaz.messagebox, "showinfo") as info, \
                mock.patch.object(interfaz.messagebox, "askyesno") as pregunta:
            interfaz.App._iniciar_sesion(v)
        self.assertIn("sin fin", info.call_args.args[1])
        pregunta.assert_not_called()
        self.assertIsNone(cfg.sesion_fin)

    def test_bloqueo_permanente_viejo_deja_quitar_horarios(self):
        cfg = Config(horarios=[Horario(TODOS, "00:00", "00:00"), Horario([0], "09:00", "10:00")])
        v = self.ventana(cfg)
        v.lista_horarios = types.SimpleNamespace(seleccion=lambda: [0])
        with mock.patch.object(interfaz.messagebox, "showwarning") as aviso:
            interfaz.App._quitar_horarios(v)
        aviso.assert_not_called()
        self.assertEqual(cfg.horarios, [Horario([0], "09:00", "10:00")])

    def test_bloqueo_normal_no_deja_quitar_horarios(self):
        cfg = Config(sesion_fin=(datetime.now() + timedelta(minutes=30)).isoformat(timespec="seconds"),
                     horarios=[Horario([0], "09:00", "10:00")])
        v = self.ventana(cfg)
        v.lista_horarios = types.SimpleNamespace(seleccion=lambda: [0])
        with mock.patch.object(interfaz.messagebox, "showwarning") as aviso:
            interfaz.App._quitar_horarios(v)
        aviso.assert_called_once()
        self.assertEqual(len(cfg.horarios), 1)


class TestApps(unittest.TestCase):
    def test_normalizar(self):
        self.assertEqual(procesos.normalizar_app(r'"C:\Program Files\Discord\Discord.exe"'),
                         ("discord.exe", None))
        self.assertEqual(procesos.normalizar_app("Steam")[0], "steam.exe")
        for prohibido in ["explorer.exe", "svchost", "pythonw.exe", "", "a|b"]:
            with self.subTest(prohibido=prohibido):
                nombre, error = procesos.normalizar_app(prohibido)
                self.assertIsNone(nombre)
                self.assertTrue(error)

    def test_listar_incluye_este_proceso(self):
        self.assertIn(os.getpid(), {pid for pid, _ in procesos.listar()})

    def test_nombres_en_ejecucion_excluye_windows(self):
        nombres = procesos.nombres_en_ejecucion()
        self.assertNotIn("explorer.exe", nombres)
        self.assertNotIn("svchost.exe", nombres)


class TestMotor(unittest.TestCase):
    def test_bloquea_y_libera(self):
        if any(n.lower() == "ping.exe" for _, n in procesos.listar()):
            self.skipTest("Ya hay un ping.exe abierto; no se toca.")
        with tempfile.TemporaryDirectory() as d:
            ruta_hosts = os.path.join(d, "hosts")
            with open(ruta_hosts, "wb") as f:
                f.write(b"127.0.0.1 localhost\r\n")
            alm = Almacen(os.path.join(d, "config.json"))
            alm.cfg.sitios = ["youtube.com"]
            alm.cfg.apps = ["ping.exe"]
            motor = Motor(alm, ruta_hosts)
            ping = subprocess.Popen(["ping", "-n", "60", "127.0.0.1"], stdout=subprocess.DEVNULL,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                estado, cerradas = motor.paso()           # sin bloqueo: no toca nada
                self.assertFalse(estado.activo)
                self.assertEqual(cerradas, [])
                self.assertIsNone(ping.poll())
                self.assertNotIn("youtube", leer(ruta_hosts))

                alm.cfg.sesion_fin = (datetime.now() + timedelta(minutes=5)).isoformat(timespec="seconds")
                estado, cerradas = motor.paso()           # con sesión: cierra ping y bloquea
                self.assertTrue(estado.activo)
                self.assertIn("ping.exe", [c.lower() for c in cerradas])
                ping.wait(timeout=5)
                self.assertIn("0.0.0.0 youtube.com", leer(ruta_hosts))
                self.assertIsNone(motor.error)

                estado, _ = motor.paso(datetime.now() + timedelta(minutes=6))   # terminó la sesión
                self.assertFalse(estado.activo)
                self.assertIsNone(alm.cfg.sesion_fin)
                self.assertNotIn("youtube", leer(ruta_hosts))
            finally:
                if ping.poll() is None:
                    ping.kill()


if __name__ == "__main__":
    unittest.main()
