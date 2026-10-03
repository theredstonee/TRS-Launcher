// Push-Benachrichtigungen auf iOS ohne APNs (Sideload): Das System weckt die App ab und zu im Hintergrund
// (BGAppRefreshTask), der Rust-Kern holt wartende Hinweise beim TRS-Server ab (§33.4) und diese Datei zeigt sie
// als lokale Benachrichtigung. Die ganze Logik (Konto, Token, Abholen, doppelte Hinweise) steckt in Rust.

import BackgroundTasks
import Foundation
import Tauri
import UIKit
import UserNotifications
import WebKit

// Aus libtrs_launcher_lib (src-tauri/src/push.rs): JSON-Liste der neuen Hinweise, freigeben mit trs_push_free.
@_silgen_name("trs_push_poll_json")
func trs_push_poll_json() -> UnsafeMutablePointer<CChar>?
@_silgen_name("trs_push_free")
func trs_push_free(_ ptr: UnsafeMutablePointer<CChar>?)

struct PushNotification: Codable {
  let id: String
  let category: String
  let title: String
  let body: String
  let target: String
  let collapse: String?
}

class PollArgs: Decodable {
  let enabled: Bool
}

class NotifyArgs: Decodable {
  let notifications: [PushNotification]
}

class TrsPushPlugin: Plugin {
  /// Muss in Info.plist unter BGTaskSchedulerPermittedIdentifiers stehen.
  static let taskId = "dev.theredstonee.trslauncher.push-poll"
  private static var registered = false
  private static var polling = false

  override func load(webview: WKWebView) {
    TrsPushPlugin.registerTask()
  }

  static func registerTask() {
    if registered { return }
    registered = true
    BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
      guard let refresh = task as? BGAppRefreshTask else {
        task.setTaskCompleted(success: false)
        return
      }
      TrsPushPlugin.handle(refresh)
    }
  }

  /// Nächsten Abruf frühestens in 15 Minuten (iOS entscheidet, wann wirklich).
  static func schedule() {
    let request = BGAppRefreshTaskRequest(identifier: taskId)
    request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
    do {
      try BGTaskScheduler.shared.submit(request)
    } catch {
      NSLog("TrsPush: Hintergrundabruf nicht planbar: \(error)")
    }
  }

  static func handle(_ task: BGAppRefreshTask) {
    if polling { schedule() }
    let queue = DispatchQueue.global(qos: .utility)
    var finished = false
    task.expirationHandler = {
      finished = true
    }
    queue.async {
      let json = poll()
      if !finished {
        show(json)
      }
      task.setTaskCompleted(success: !finished)
    }
  }

  static func poll() -> [PushNotification] {
    guard let ptr = trs_push_poll_json() else { return [] }
    defer { trs_push_free(ptr) }
    let data = Data(String(cString: ptr).utf8)
    return (try? JSONDecoder().decode([PushNotification].self, from: data)) ?? []
  }

  static func show(_ list: [PushNotification]) {
    let center = UNUserNotificationCenter.current()
    for n in list.prefix(50) {
      let content = UNMutableNotificationContent()
      content.title = String(n.title.prefix(80))
      content.body = String(n.body.prefix(200))
      content.sound = .default
      content.threadIdentifier = n.category
      content.userInfo = ["target": n.target]
      let request = UNNotificationRequest(identifier: n.collapse ?? n.id, content: content, trigger: nil)
      center.add(request, withCompletionHandler: nil)
    }
  }

  @objc public func state(_ invoke: Invoke) {
    invoke.resolve(["distributors": [], "temporary": false, "deviceName": UIDevice.current.name])
  }

  @objc public func setPoll(_ invoke: Invoke) {
    do {
      let args = try invoke.parseArgs(PollArgs.self)
      TrsPushPlugin.polling = args.enabled
      if args.enabled {
        TrsPushPlugin.schedule()
      } else {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: TrsPushPlugin.taskId)
      }
      invoke.resolve()
    } catch {
      invoke.reject("invalid_args", code: "invalid_args")
    }
  }

  @objc public func notify(_ invoke: Invoke) {
    do {
      let args = try invoke.parseArgs(NotifyArgs.self)
      TrsPushPlugin.show(args.notifications)
      invoke.resolve()
    } catch {
      invoke.reject("invalid_args", code: "invalid_args")
    }
  }

  /// UnifiedPush gibt es auf iOS nicht.
  @objc public func register(_ invoke: Invoke) {
    invoke.reject("unsupported", code: "unsupported")
  }

  @objc public func unregister(_ invoke: Invoke) {
    invoke.resolve()
  }
}

@_cdecl("init_plugin_trs_push")
func initPlugin() -> Plugin {
  return TrsPushPlugin()
}
