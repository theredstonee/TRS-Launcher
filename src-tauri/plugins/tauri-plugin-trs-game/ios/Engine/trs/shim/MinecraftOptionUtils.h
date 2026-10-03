// TRS-Ersatz für Amethysts MinecraftOptionUtils.h (options.txt verwaltet der TRS-Kern).
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

#pragma once

#import <Foundation/Foundation.h>

@interface MinecraftOptionUtils : NSObject
+ (instancetype)sharedInstance;
- (void)updateMCGuiScale;
@end
