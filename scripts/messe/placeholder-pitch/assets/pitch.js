// Platzhalter-Präsentation für den Messe-Build (wird durch das echte Paket ersetzt).
// Vertrag: ?mode=loop&lang=de|en&demo=1, Ereignis „trs-pitch:demo“ + postMessage an das Elternfenster,
// window.TRSPitch = { play(), pause(), goTo(i) }.
(function () {
  var params = new URLSearchParams(location.search)
  var lang = params.get('lang') === 'en' ? 'en' : 'de'
  var loop = params.get('mode') === 'loop'
  var demo = params.get('demo') === '1'
  var texts = {
    de: {
      cta: 'Jetzt selbst ausprobieren',
      slides: [
        ['TRS Launcher', 'Ein Launcher für jede Minecraft-Version', 'Fabric, Quilt, Forge und NeoForge – alles an einem Ort.'],
        ['Modpacks', 'Mit einem Klick installiert', 'Modrinth und CurseForge, teilen per Code oder Link.'],
        ['TRS Client', 'Mehr FPS, eigene Umhänge', 'Minimap, Emotes, Clips und Redstone-Werkzeuge im Spiel.'],
        ['Gemeinsam', 'Freunde, Chat und Welten', 'Welten hosten ohne Server – direkt aus dem Launcher.'],
      ],
    },
    en: {
      cta: 'Try it yourself',
      slides: [
        ['TRS Launcher', 'One launcher for every Minecraft version', 'Fabric, Quilt, Forge and NeoForge – all in one place.'],
        ['Modpacks', 'Installed in one click', 'Modrinth and CurseForge, share by code or link.'],
        ['TRS Client', 'More FPS, your own capes', 'Minimap, emotes, clips and redstone tools in game.'],
        ['Together', 'Friends, chat and worlds', 'Host worlds without a server – right from the launcher.'],
      ],
    },
  }[lang]
  document.documentElement.lang = lang
  var stage = document.getElementById('stage')
  var dots = document.getElementById('dots')
  var cta = document.getElementById('cta')
  var slides = texts.slides.map(function (s) {
    var el = document.createElement('section')
    el.className = 'slide'
    var tag = document.createElement('div')
    tag.className = 'tag'
    tag.textContent = s[0]
    var h = document.createElement('h1')
    h.textContent = s[1]
    var p = document.createElement('p')
    p.textContent = s[2]
    el.append(tag, h, p)
    stage.appendChild(el)
    var dot = document.createElement('span')
    dots.appendChild(dot)
    return { el: el, dot: dot }
  })
  var current = 0
  var timer = 0
  var playing = false
  function show(i) {
    current = ((i % slides.length) + slides.length) % slides.length
    slides.forEach(function (s, n) {
      s.el.classList.toggle('on', n === current)
      s.dot.classList.toggle('on', n === current)
    })
  }
  function schedule() {
    clearTimeout(timer)
    if (!playing) return
    timer = setTimeout(function () {
      if (!loop && current === slides.length - 1) return pause()
      show(current + 1)
      schedule()
    }, 6000)
  }
  function play() { playing = true; schedule() }
  function pause() { playing = false; clearTimeout(timer) }
  function goTo(i) { show(i); schedule() }
  function requestDemo() {
    window.dispatchEvent(new CustomEvent('trs-pitch:demo'))
    if (window.parent && window.parent !== window) window.parent.postMessage({ source: 'trs-pitch', type: 'demo' }, '*')
  }
  if (demo) {
    cta.textContent = texts.cta
    cta.hidden = false
    cta.addEventListener('click', requestDemo)
    document.addEventListener('keydown', requestDemo)
  }
  window.TRSPitch = { play: play, pause: pause, goTo: goTo }
  show(0)
  play()
})()
