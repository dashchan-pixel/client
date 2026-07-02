package com.mishiranu.dashchan.content.net;

import android.net.Uri;
import chan.content.Chan;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Lists and downloads repository files through the GitHub REST API. */
public class GithubRepository {
	public static class Entry {
		public final boolean directory;
		public final Uri downloadUri;

		Entry(boolean directory, Uri downloadUri) {
			this.directory = directory;
			this.downloadUri = downloadUri;
		}
	}

	private final HttpHolder holder;
	private final String repositoryPath;

	/** @param githubUri repository URI, e.g. {@code https://github.com/owner/repo} */
	public GithubRepository(HttpHolder holder, Uri githubUri) {
		this.holder = holder;
		String path = githubUri.getPath();
		if (path == null || path.isEmpty()) {
			throw new IllegalArgumentException("Invalid GitHub URI: " + githubUri);
		}
		repositoryPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
	}

	/** Returns a map of file names to entries for the given repository directory. */
	public Map<String, Entry> listFiles(String path) throws HttpException, JSONException {
		Uri uri = Uri.parse("https://api.github.com").buildUpon()
				.appendEncodedPath("repos" + repositoryPath + "/contents/" + path).build();
		String response = new HttpRequest(uri, holder)
				.addHeader("Accept", "application/vnd.github+json")
				.perform().readString();
		JSONArray array = new JSONArray(response);
		LinkedHashMap<String, Entry> files = new LinkedHashMap<>();
		for (int i = 0; i < array.length(); i++) {
			JSONObject item = array.getJSONObject(i);
			String name = item.getString("name");
			boolean directory = "dir".equals(item.optString("type"));
			String downloadUrl = item.isNull("download_url") ? null : item.optString("download_url", null);
			files.put(name, new Entry(directory, downloadUrl != null ? Uri.parse(downloadUrl) : null));
		}
		return files;
	}

	public byte[] readFile(Entry entry) throws HttpException {
		if (entry.downloadUri == null) {
			return null;
		}
		return new HttpRequest(entry.downloadUri, holder).perform().readBytes();
	}

	public static HttpHolder createHolder() {
		return new HttpHolder(Chan.getFallback());
	}
}
