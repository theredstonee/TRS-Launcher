// TRS-Ersatz für Amethysts UIKit+hook.h: nur UIWindow.mainWindow wird gebraucht.
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

#pragma once

#import <UIKit/UIKit.h>

@interface UIWindow (TRSMainWindow)
+ (UIWindow *)mainWindow;
@end
