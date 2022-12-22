const findImageSourceLinkSelector = ".WpHeLc.VfPpkd-mRLv6.VfPpkd-RLmnJb";
const findImageSourceLink = document.querySelector(findImageSourceLinkSelector);

/* If there is no findImageSourceLink it means that the url that was used for search does not point to an image */
if (findImageSourceLink == null) {
  AndroidBridge.handleNoImageAtThatUrl();
} else {
  function getFindImageSourceUrl() {
    return findImageSourceLink.href;
  }

  function findImageSourceUrlValid(url) {
    return url != null && url.includes("www.google.com/search");
  }

  /* findImageSourceUrl may not be available immediately, in that case observe findImageSourceLink for href changes */
  let findImageSourceUrl = getFindImageSourceUrl();
  if (findImageSourceUrlValid(findImageSourceUrl)) {
    AndroidBridge.handleFindImageSourceUrl(findImageSourceUrl);
  } else {
    const observer = new MutationObserver(function (
      mutations,
      mutationInstance
    ) {
      findImageSourceUrl = getFindImageSourceUrl();
      if (findImageSourceUrlValid(findImageSourceUrl)) {
        mutationInstance.disconnect();
        AndroidBridge.handleFindImageSourceUrl(findImageSourceUrl);
      }
    });
    observer.observe(findImageSourceLink, {
      attributes: ["href"],
    });
  }
}
