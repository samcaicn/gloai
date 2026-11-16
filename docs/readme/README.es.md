<!-- translated-from: README.md @ 7b09b8113580 -->
<div align="center">

<img src="../media/logo.png" alt="Mika" width="88" />

# Mika

**Un asistente de IA en tu móvil: dile lo que necesitas y usará tus apps y la web hasta dejarlo hecho.**

Trabaja en una pantalla en segundo plano que no ves, así que puedes seguir usando el móvil con normalidad.

<a href="https://github.com/wangzan101/Mika"><img src="../media/star-banner-v2.png" alt="¿Te gusta Mika? Dale una estrella — haz clic en Star arriba a la derecha" width="620" /></a>

<br/>
<a href="https://github.com/wangzan101/Mika/releases/latest"><img src="https://img.shields.io/badge/Descargar%20APK-Android%2011%2B-2ea043?style=for-the-badge&logo=android&logoColor=white" alt="Descargar APK" /></a> <a href="https://github.com/wangzan101/Mika"><img src="https://img.shields.io/github/stars/wangzan101/Mika?style=for-the-badge&logo=github&label=Star&color=FFD700&logoColor=white" alt="Star this repo" /></a>

<a href="https://github.com/wangzan101/Mika/releases"><img src="https://img.shields.io/github/downloads/wangzan101/Mika/total?label=descargas&color=2ea043" alt="downloads" /></a>
<a href="https://github.com/wangzan101/Mika/releases/latest"><img src="https://img.shields.io/github/v/release/wangzan101/Mika?include_prereleases&label=versi%C3%B3n" alt="version" /></a>
<a href="../../LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-lightgrey" alt="license" /></a>

<br/>

<a href="https://trendshift.io/repositories/326940#achievements"><img src="https://trendshift.io/api/badge/trendshift/repositories/326940/daily?language=Kotlin" alt="#1 Kotlin Repository of the Day" width="250" height="55" /></a>
<a href="https://trendshift.io/repositories/326940#achievements"><img src="https://trendshift.io/api/badge/trendshift/repositories/326940/weekly?language=Kotlin" alt="#1 Kotlin Repository of the Week" width="250" height="55" /></a>
<a href="https://trendshift.io/repositories/326940#achievements"><img src="https://trendshift.io/api/badge/trendshift/repositories/326940/daily" alt="#5 Repository of the Day" width="250" height="55" /></a>

<br/>

[English](../../README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · Español · [Türkçe](README.tr.md)

<sub>Esta versión en español es una traducción del original en chino simplificado. Si ves algo que suena raro o que no coincide con el original, te agradecemos que lo corrijas con un PR.</sub>

<br/>

<img src="../media/wechat-send-side.gif" alt="Con una sola frase, Mika abre WeChat en su pantalla en segundo plano, busca el contacto y envía el mensaje" width="640" />

<sub>Con la frase «Envía un mensaje a Transferencia de archivos»: a la izquierda, la conversación con Mika; a la derecha, cada paso en su pantalla en segundo plano (a velocidad 4×, con la lista de chats difuminada)</sub>

</div>

## Una frase, una tarea resuelta

<table>
<tr>
<td align="center" width="33%"><img src="../media/wechat-group.gif" width="230" alt="Lee un grupo de WeChat de principio a fin y resume lo que se habló hoy" /><br/><b>«¿De qué se habló hoy en el grupo?»</b><br/><sub>Repasa el chat en segundo plano y luego lo resume</sub></td>
<td align="center" width="33%"><img src="../media/amazon-cart.gif" width="230" alt="Elige un cargador en una tienda online y lo añade al carrito" /><br/><b>«Un cargador de menos de 30 dólares y 4,5 estrellas o más»</b><br/><sub>Lo añade al carrito y se detiene antes de pagar</sub></td>
<td align="center" width="33%"><img src="../media/xhs-summary.gif" width="230" alt="Resume las publicaciones recientes de un creador" /><br/><b>«¿Qué ha publicado últimamente este creador?»</b><br/><sub>Abre la app, lee publicación por publicación y lo ordena</sub></td>
</tr>
</table>

<details>
<summary>Más demos</summary>

<table>
<tr>
<td align="center" width="33%"><img src="../media/boss-greet.gif" width="230" alt="Saluda a los reclutadores de 3 ofertas en un portal de empleo" /><br/>Saluda a los reclutadores de 3 ofertas en un portal de empleo</td>
<td align="center" width="33%"><img src="../media/google-flights.gif" width="230" alt="Busca el vuelo directo más barato" /><br/>Busca el vuelo directo más barato de San Francisco a Nueva York</td>
<td align="center" width="33%"><img src="../media/hacker-news.gif" width="230" alt="Lee en voz alta las 5 primeras noticias y su puntuación" /><br/>Lee las 5 primeras noticias de un sitio de noticias</td>
</tr>
<tr>
<td align="center"><img src="../media/job-greet.jpg" width="230" alt="Busca ofertas de empleo y saluda a los reclutadores" /><br/>Busca ofertas y saluda a los reclutadores</td>
<td align="center"><img src="../media/music-vip.jpg" width="230" alt="Mira anuncios para conseguir ventajas en una app de música" /><br/>Mira anuncios para conseguir ventajas de música</td>
<td align="center"><img src="../media/food-order.jpg" width="230" alt="Pide comida a domicilio combinando descuentos y te pregunta antes de pagar" /><br/>Pide comida combinando descuentos y pregunta antes de pagar</td>
</tr>
</table>

</details>

<sub>En varias demos aparecen WeChat, una app de mensajería, y Xiaohongshu (RED), una red social de estilo de vida. Mika no depende de ellas: funciona con cualquier app o sitio web de tu móvil.</sub>

## Por qué Mika

| | |
|---|---|
| 📱 **No te quita la pantalla** | Abre apps, toca, escribe y se desplaza en una pantalla aparte en segundo plano mientras tú usas el móvil como siempre; si quieres mirar, toca la vista en miniatura |
| 🧩 **Funciona con cualquier app** | Mira la pantalla y encuentra los botones como lo haría una persona: no necesita APIs de la app ni integraciones previas |
| 🌐 **Llámalo desde cualquier página web** | En el navegador integrado, di lo que quieres en la página que estás leyendo y trabajará ahí mismo; si tocas la página, se detiene |
| 🧠 **Mejora con el uso** | Recuerda las preferencias que le cuentas y convierte las tareas que completa en habilidades, para hacerlas más rápido y con más fiabilidad la próxima vez |
| 🔒 **Tus datos se quedan en tu móvil** | No hay ningún servidor de Mika; usa tu propia clave de API del modelo o inicia sesión con una cuenta de ChatGPT, xAI u OpenRouter |
| 🆓 **Código abierto y gratis** | Apache-2.0, todo el código es público |

## Empieza en tres pasos

1. **Instálalo**: descarga el APK desde [Releases](https://github.com/wangzan101/Mika/releases/latest).
2. **Conecta un modelo**: en Ajustes › Modelo, elige un proveedor e introduce una clave de API, o simplemente inicia sesión con una cuenta ([modelos e inicio de sesión](../models.en.md)).
3. **Para que maneje apps del móvil**: sigue el asistente «Set up phone control» de los ajustes y empareja la depuración inalámbrica una sola vez ([guía de configuración](../setup-guide.en.md)). Si solo quieres chatear, navegar y trabajar con archivos, puedes saltarte este paso.

Después, prueba a decirle: «Pon alarmas mañana a las 9:40 y a las 10:00» o «Mira el tiempo cada mañana a las 8 y avísame solo si va a llover».

> 💡 ¿Tienes un ordenador a mano? Envía esta frase al agente de IA de tu ordenador y lo instalará todo por ti:<br/>
> `Sigue los pasos de https://github.com/wangzan101/Mika/blob/main/docs/install-with-agent.en.md para instalar Mika en el móvil Android conectado por USB.`

## Qué puede hacer

| | |
|---|---|
| **Manejar apps** | Lee listas largas e historiales de chat, toca y escribe, comparte texto con las apps; los inicios de sesión, códigos de verificación y pagos te los deja a ti |
| **Navegar por la web** | Un navegador integrado de tipo escritorio que mantiene tus sesiones, lee páginas, descarga y sube archivos; marca su propio ritmo al pasar páginas y, si algo lo bloquea, te lo pasa |
| **Lo que tienes en pantalla** | Toca dos veces la cápsula flotante para enviarle la pantalla que estás viendo: «¿qué le respondo?», «apúntame esto» |
| **Horarios y condiciones** | «Recuérdamelo mañana a las 9», «haz esto todos los viernes»; se ejecuta a su hora en la conversación original |
| **Interrúmpelo o páralo cuando quieras** | Sigue hablándole mientras trabaja para añadir o corregir cosas (mantén pulsado un mensaje que aún no haya recogido para retirarlo o editarlo); al parar te dice hasta dónde llegó, y con «sigue» retoma desde ahí |
| **Mantén pulsado para hablar** | Mantén pulsado el cuadro de texto para dictar y corrige antes de enviar; mantén pulsada la cápsula flotante para hablar y suelta para enviarlo directamente. Usa el reconocimiento de voz del teléfono o, si no tiene, un modelo de voz de tu proveedor |
| **Archivos y cálculo** | Un sandbox de Linux por conversación ejecuta Python, Node y shell sobre los archivos que le envías (arm64) |
| **Desde tu ordenador** | Las herramientas de IA de tu ordenador pueden encargarle tareas a Mika mediante CLI + Skill o MCP ([usar Mika desde un ordenador](../computer.en.md)) |

Por defecto, Mika funciona en **modo Preguntar**: solo te consulta antes de gastar dinero (hacer pedidos, pagar) y todo lo demás lo hace sin más. También puedes cambiar al **modo Autónomo**. Consulta [Preguntar y Autónomo](../modes.en.md).

Para ver cómo funciona cada cosa y los detalles, consulta las [funciones en detalle](../features.en.md).

<details>
<summary>Apps y sitios web con conocimiento de uso integrado (los que no aparecen también funcionan)</summary>

La lista completa está en la sección [What it can do](../../README.md#what-it-can-do) del README en inglés y se actualiza sola a medida que cambia el conocimiento integrado.

</details>

<details>
<summary>Proveedores de modelos compatibles</summary>

Los proveedores predefinidos y los tipos de API a los que se puede conectar con «Custom» aparecen en la sección [What it can do](../../README.md#what-it-can-do) del README en inglés, que se actualiza sola a partir del código.

También puedes prescindir de la clave e iniciar sesión con una cuenta de ChatGPT (Plus / Pro), xAI / Grok u OpenRouter. Los pasos para añadir un modelo y lo que conviene saber de cada inicio de sesión están en [modelos e inicio de sesión](../models.en.md).

</details>

## En qué móviles funciona

| | Requisito |
|---|---|
| Chat, web, archivos | Android 11 o posterior |
| Manejar apps del móvil | Android 12 o posterior; **se recomienda Android 14+** (la pantalla en segundo plano tiene su propio foco, así que no interfiere cuando escribes en la pantalla principal) |
| Sandbox de Linux | Dispositivos arm64 |

¿No sabes si tu móvil sirve? Ejecuta Ajustes › Recuperación y reparación › **Autocomprobación de compatibilidad**: 12 comprobaciones en un minuto, y puedes [enviarnos el resultado](https://github.com/wangzan101/Mika/issues/new?template=device-report.yml) con un toque.

## Privacidad y seguridad

- **No hay ningún servidor de Mika**: lo que dices y lo que hay en tu pantalla solo se envía al proveedor de modelos que tú configuras.
- Las claves de API y los recuerdos se guardan cifrados en el móvil; las conversaciones y los registros de ejecución se quedan en el dispositivo; para buscar actualizaciones solo se contacta con GitHub.
- Manejar automáticamente apps y sitios web de terceros puede ir en contra de sus condiciones de uso, así que decide tú. Para lo importante, usa el modo Preguntar y revisa los resultados. Consulta el [aviso legal](../disclaimer.en.md).

<details>
<summary><b>Preguntas frecuentes</b></summary>

**¿La depuración inalámbrica se desactiva sola?** Android la desactiva al cambiar de Wi-Fi o al reiniciar. Si se lo permites, Mika la vuelve a activar por su cuenta; si aun así no conecta, vuelve a «Set up phone control» y empareja de nuevo.

**¿No encuentras la depuración inalámbrica en tu móvil?** Algunos sistemas no la incluyen. En ese caso puedes iniciar el asistente del móvil desde un ordenador por USB; consulta la [guía de configuración](../setup-guide.en.md).

**¿Hay pantallas que no se pueden leer ni capturar?** Las pantallas de pago o de introducción de contraseñas bloquean las capturas, así que Mika te pide que te encargues tú.

**¿Puedo usar un modelo que se ejecuta en mi propio ordenador?** Sí: elige «Custom» como proveedor e introduce su dirección en tu red local.

**¿La app está en español?** Sí. La interfaz está en español, inglés, árabe, chino simplificado, chino tradicional, japonés, coreano, portugués, ruso e indonesio, sigue el idioma del sistema y se puede cambiar en Ajustes › Idioma. Puedes hablarle a Mika en español sin problema. El conocimiento integrado sobre apps y sitios web está escrito en chino, y el modelo lo entiende sin dificultad.

**¿De dónde vienen las actualizaciones?** Solo de los [Releases](https://github.com/wangzan101/Mika/releases) de este repositorio; también puedes actualizar desde la app en Acerca de Mika › Buscar actualizaciones.

Hay más respuestas en las [preguntas frecuentes](../faq.en.md).

</details>

## Comunidad y contribuciones

- **Documentación**: toda la documentación y un orden de lectura sugerido están en el [índice de documentación](../README.en.md)
- **Comunidad**: únete al [chat de grupo en X](https://x.com/i/chat/group_join/g2116926829198246025/48B52j5svL); para escribir al autor por WeChat, escanea el código QR de la app en Acerca de Mika › Contactar con el autor (menciona «Mika» en la solicitud)
- **Sigue al autor**: X [@wangzan101](https://x.com/wangzan101)
- **Informa de un problema**: [abre un issue](https://github.com/wangzan101/Mika/issues/new/choose); en la app, Acerca de Mika › Enviar comentarios permite adjuntar información de diagnóstico
- **Contribuye**: añade conocimiento de uso para una app o un sitio web (texto plano, sin programar) o contribuye con código; consulta la [guía de contribución](../../.github/CONTRIBUTING.md)

<p align="center">⭐ Si Mika te ahorra tiempo, dale una estrella para que más gente lo encuentre.</p>

## Star History

<a href="https://star-history.com/#wangzan101/Mika&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=wangzan101/Mika&type=Date&theme=dark&v=20261110-2017" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=wangzan101/Mika&type=Date&v=20261110-2017" width="600" />
  </picture>
</a>

## Colaboradores

Gracias a todas las personas que han participado en Mika:

<a href="https://github.com/wangzan101/Mika/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=wangzan101/Mika&max=100&columns=12" alt="Contributors" />
</a>

## Licencia

[Apache-2.0](../../LICENSE) · [NOTICE](../../NOTICE) · [Componentes de terceros](../../licenses/THIRD_PARTY_NOTICES.md)
