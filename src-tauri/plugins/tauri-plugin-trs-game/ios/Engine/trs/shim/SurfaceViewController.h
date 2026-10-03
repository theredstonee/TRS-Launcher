// TRS-Ersatz für Amethysts SurfaceViewController.h: der Amethyst-Code braucht nur die
// Zeichenfläche, den Laufzustand und den Absturz-Wartepunkt. Die echte Spielansicht ist
// GameViewController (Swift).
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

#pragma once

#import <UIKit/UIKit.h>
#import "UIKit+hook.h"
#import "PLLogOutputView.h"

extern BOOL canAppendToLog;
extern dispatch_group_t fatalExitGroup;

@interface SurfaceViewController : NSObject
/// View, dessen Layer gerendert wird (gesetzt über trs_engine_set_surface).
+ (UIView *)surface;
+ (BOOL)isRunning;
- (void)updateGrabState;
@end
