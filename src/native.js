import { Capacitor } from '@capacitor/core';
import { SpeechRecognition } from '@capacitor-community/speech-recognition';
import { Filesystem, Directory } from '@capacitor/filesystem';
import { Share } from '@capacitor/share';
import { KeepAwake } from '@capacitor-community/keep-awake';
import { App } from '@capacitor/app';
window.Native = { isNative: Capacitor.isNativePlatform(), SpeechRecognition, Filesystem, Directory, Share, KeepAwake, App };
