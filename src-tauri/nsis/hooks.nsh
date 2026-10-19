; Installer-Hooks für den NSIS-Installer (bundle.windows.nsis.installerHooks).
;
; NSIS_HOOK_PREINSTALL läuft vor Tauris eigener Prüfung „App läuft?“ und vor dem Kopieren der Dateien.
; Tauri beendet einen laufenden Launcher zwar selbst, wartet danach aber nur 500 ms. Gibt Windows die alte
; trs-launcher.exe bis dahin nicht frei (Virenscanner prüft die Datei, Launcher beendet sich langsam), bricht
; das Kopieren mit „Fehler beim Überschreiben der Datei …\trs-launcher.exe“ ab. Hier wird deshalb beendet und
; gewartet, bis die Datei wirklich beschreibbar ist. Nur der Launcher wird beendet, ein laufendes Spiel nicht.

!macro NSIS_HOOK_PREINSTALL
  nsis_tauri_utils::FindProcessCurrentUser "${MAINBINARYNAME}.exe"
  Pop $R0
  ${If} $R0 = 0
    IfSilent trs_kill 0
    ${If} $PassiveMode != 1
      nsis_tauri_utils::StrReplace "$(appRunningOkKill)" "{{product_name}}" "${PRODUCTNAME}"
      Pop $R2
      MessageBox MB_OKCANCEL|MB_ICONINFORMATION $R2 IDOK trs_kill
      Abort
    ${EndIf}
    trs_kill:
    nsis_tauri_utils::KillProcessCurrentUser "${MAINBINARYNAME}.exe"
    Pop $R0
  ${EndIf}

  ; Bis zu 30 s warten, bis die alte .exe beschreibbar ist (60 × 500 ms).
  StrCpy $R5 0
  trs_wait:
    IfFileExists "$INSTDIR\${MAINBINARYNAME}.exe" 0 trs_free
    ClearErrors
    FileOpen $R6 "$INSTDIR\${MAINBINARYNAME}.exe" a
    IfErrors 0 trs_close
    IntOp $R5 $R5 + 1
    ${If} $R5 < 60
      Sleep 500
      ; Zwischendurch erneut beenden, falls der Launcher wieder gestartet wurde.
      ${If} $R5 = 10
      ${OrIf} $R5 = 30
        nsis_tauri_utils::KillProcessCurrentUser "${MAINBINARYNAME}.exe"
        Pop $R0
      ${EndIf}
      Goto trs_wait
    ${EndIf}
    IfSilent trs_free
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "${PRODUCTNAME} kann gerade nicht aktualisiert werden, weil trs-launcher.exe noch verwendet wird.$\r$\n$\r$\nBitte schließe den Launcher (Task-Manager → trs-launcher.exe beenden) und klicke auf „Wiederholen“. Hilft das nicht, starte den PC neu.$\r$\n$\r$\n${PRODUCTNAME} can't be updated because trs-launcher.exe is still in use. Close it in Task Manager and click Retry, or restart your PC." IDRETRY trs_retry
    Abort
    trs_retry:
      nsis_tauri_utils::KillProcessCurrentUser "${MAINBINARYNAME}.exe"
      Pop $R0
      StrCpy $R5 0
      Goto trs_wait
  trs_close:
    FileClose $R6
  trs_free:
!macroend
