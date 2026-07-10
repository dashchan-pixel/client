#!/bin/bash -e

# icon-logo.svg: white silhouette only (ic_logo, ic_notification)
sed -e 's/id="dash" style="display:none"/id="dash" style="display:inline"/' \
	-e 's/id="dash-group" style="display:inline"/id="dash-group" style="display:none"/' \
	'icon.svg' > 'icon-logo.svg'
# cropped variants for legacy launcher / logo exports (78x78 center area)
sed -e 's/viewBox="0 0 108 108"/viewBox="15 15 78 78"/' 'icon.svg' > 'icon-cropped.svg'
sed -e 's/viewBox="0 0 108 108"/viewBox="15 15 78 78"/' 'icon-logo.svg' > 'icon-logo-cropped.svg'

dimensions=(mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4)
for dimension in ${dimensions[@]}; do
	resource="${dimension%:*}"
	scale="${dimension#*:}"
	mkdir -p "res/mipmap-$resource" "res/drawable-$resource"
	out_launcher="res/mipmap-$resource/ic_launcher.png"
	out_foreground="res/drawable-$resource/ic_launcher_foreground.png"
	out_notification="res/drawable-$resource/ic_notification.png"
	out_logo="res/mipmap-$resource/ic_logo.png"
	out=("$out_launcher" "$out_foreground" "$out_logo" "$out_notification")
	size="$(bc <<< "48 * $scale / 1")"
	rsvg-convert 'icon-cropped.svg' -w "$size" -h "$size" -o "$out_launcher"
	rsvg-convert 'icon-logo-cropped.svg' -w "$size" -h "$size" -o "$out_logo"
	size="$(bc <<< "108 * $scale / 1")"
	rsvg-convert 'icon.svg' -w "$size" -h "$size" -o "$out_foreground"
	size="$(bc <<< "24 * $scale / 1")"
	rsvg-convert 'icon-logo-cropped.svg' -w "$size" -h "$size" -o "$out_notification"
	if command -v optipng > /dev/null; then
		optipng -quiet "${out[@]}"
	fi
	if command -v exiftool > /dev/null; then
		exiftool -all= -overwrite_original "${out[@]}"
	fi
done
rm -f 'icon-logo.svg' 'icon-cropped.svg' 'icon-logo-cropped.svg'
