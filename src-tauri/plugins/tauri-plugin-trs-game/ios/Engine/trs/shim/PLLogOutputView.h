// TRS-Ersatz für Amethysts PLLogOutputView.h (Log-Ansicht gibt es in TRS im Launcher).
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

#pragma once

#import <Foundation/Foundation.h>

@interface PLLogOutputView : NSObject
+ (void)appendToLog:(NSString *)line;
/// YES = der aufrufende Thread soll warten, bis der Nutzer den Absturz bestätigt hat.
+ (BOOL)handleExitCode:(int)code;
@end
