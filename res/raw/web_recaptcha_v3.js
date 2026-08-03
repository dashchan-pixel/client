// Injected into the site's own page once it has loaded. Version 3 scores what it can observe of
// the client, so it is run where the site runs it -- a real page, under the real origin, with the
// cookies and the history that belong to it -- rather than in a page of ours that has none of that
// and is scored at the floor for it.

(function () {
	var attempts = 0

	function attempt() {
		// The site loads the script itself, and a page still fetching it has no grecaptcha yet.
		// A page that never grows one is not the page that was asked for -- an interstitial, most
		// likely -- and waiting on it forever would hang the post behind it.
		if (typeof grecaptcha === 'undefined' || !grecaptcha.execute) {
			if (++attempts > 60) {
				jsi.onError()
			} else {
				setTimeout(attempt, 500)
			}
			return
		}
		grecaptcha.ready(function () {
			var action = '__REPLACE_ACTION__'
			var promise = action ? grecaptcha.execute('__REPLACE_API_KEY__', { action: action })
				: grecaptcha.execute('__REPLACE_API_KEY__')
			promise.then(function (response) {
				jsi.onResponse(response || '')
			}, function () {
				jsi.onError()
			})
		})
	}

	attempt()
})()
