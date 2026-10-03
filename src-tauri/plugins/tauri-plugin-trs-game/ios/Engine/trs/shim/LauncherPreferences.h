// TRS-Ersatz für Amethysts LauncherPreferences.h: feste Standardwerte statt Einstellungsdatei.
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

#pragma once

#import <UIKit/UIKit.h>

id getPrefObject(NSString *key);
BOOL getPrefBool(NSString *key);
float getPrefFloat(NSString *key);
NSInteger getPrefInt(NSString *key);

BOOL getEntitlementValue(NSString *key);
