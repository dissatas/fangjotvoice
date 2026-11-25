#!/usr/bin/env bash
# Copies libraries/fonts into www and bundles the native bridge. Run: npm run build
set -e
mkdir -p www/lib www/fonts
cp node_modules/html2pdf.js/dist/html2pdf.bundle.min.js node_modules/jszip/dist/jszip.min.js node_modules/qrcodejs/qrcode.min.js www/lib/
for f in ibm-plex-sans-thai inter inter-tight; do mkdir -p www/fonts/$f; cp -r node_modules/@fontsource/$f/files www/fonts/$f/; done
: > www/fonts/fonts.css
for w in 400 500 600 700; do sed 's#url(./files/#url(./ibm-plex-sans-thai/files/#g' node_modules/@fontsource/ibm-plex-sans-thai/thai-$w.css node_modules/@fontsource/ibm-plex-sans-thai/latin-$w.css >> www/fonts/fonts.css; done
for w in 400 500 600; do sed 's#url(./files/#url(./inter/files/#g' node_modules/@fontsource/inter/latin-$w.css >> www/fonts/fonts.css; done
for w in 600 700; do sed 's#url(./files/#url(./inter-tight/files/#g' node_modules/@fontsource/inter-tight/latin-$w.css >> www/fonts/fonts.css; done
# keep only woff2 actually referenced
for d in www/fonts/*/files; do for x in $d/*; do grep -q "$(basename $x)" www/fonts/fonts.css || rm -f $x; done; done
npx esbuild src/native.js --bundle --format=iife --minify --outfile=www/native.js
