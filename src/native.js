import { Capacitor, registerPlugin } from '@capacitor/core';
import { Filesystem, Directory } from '@capacitor/filesystem';
import { Share } from '@capacitor/share';
import { KeepAwake } from '@capacitor-community/keep-awake';
import { App } from '@capacitor/app';
// Local plugin (android/app/src/main/java/com/fangjot/app): background recording service
const Rec = registerPlugin('FangjotRecorder');
window.Native = { isNative: Capacitor.isNativePlatform(), Rec, Filesystem, Directory, Share, KeepAwake, App };
