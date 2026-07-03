package com.mishiranu.dashchan.util

import java.util.Locale
import java.util.regex.Pattern

class SearchHelper(private val advancedMode: Boolean) {
	private enum class Search { NONE, INCLUDE, EXCLUDE }

	private val flags = HashMap<String, Search>()
	private val resultInclude = HashSet<String>()
	private val resultExclude = HashSet<String>()

	fun setFlags(vararg flags: String) {
		this.flags.clear()
		for (flag in flags) {
			this.flags[flag] = Search.NONE
		}
	}

	fun handleQueries(locale: Locale, query: String): HashSet<String> {
		var queryBuilder: StringBuilder? = null
		var shift = 0
		val queries = HashSet<String>()
		val include = resultInclude
		val exclude = resultExclude
		include.clear()
		exclude.clear()
		if (advancedMode) {
			var workQuery = query
			val matcher = PATTERN_LONG_QUERY_PART.matcher(workQuery)
			while (matcher.find()) {
				val start = matcher.start()
				val end = matcher.end()
				val remove = end - start
				if (queryBuilder == null) {
					queryBuilder = StringBuilder(workQuery)
				}
				queryBuilder.delete(start - shift, end - shift)
				shift += remove
				val excludePart = matcher.group(1)
				val queryPart = matcher.group(2)!!
				if (queryPart.isNotEmpty()) {
					val lowQueryPart = queryPart.lowercase(locale)
					if ("-" == excludePart) {
						exclude.add(lowQueryPart)
					} else {
						include.add(lowQueryPart)
						queries.add(queryPart)
					}
				}
			}
			if (queryBuilder != null) {
				workQuery = queryBuilder.toString()
			}
			val splitted = workQuery.split(Regex(" +"))
			outer@ for (queryPart in splitted) {
				var lowQueryPart = queryPart.lowercase(locale)
				for (flag in flags.keys) {
					if (":$flag" == lowQueryPart) {
						flags[flag] = Search.INCLUDE
						continue@outer
					} else if (":-$flag" == lowQueryPart) {
						flags[flag] = Search.EXCLUDE
						continue@outer
					}
				}
				if (lowQueryPart.startsWith("-")) {
					lowQueryPart = lowQueryPart.substring(1)
					if (lowQueryPart.isNotEmpty()) {
						exclude.add(lowQueryPart)
					}
				} else if (lowQueryPart.isNotEmpty()) {
					include.add(lowQueryPart)
					queries.add(queryPart)
				}
			}
		} else {
			queries.add(query)
			include.add(query.lowercase(locale))
		}
		return queries
	}

	private val flagsState = HashMap<String, Boolean>()

	// flag-state (String-Boolean) alternation
	fun checkFlags(vararg alternation: Any?): Boolean {
		var i = 0
		while (i < alternation.size) {
			flagsState[alternation[i] as String] = alternation[i + 1] as Boolean
			i += 2
		}
		return checkFlags(flagsState)
	}

	private fun checkFlags(flagsState: HashMap<String, Boolean>): Boolean {
		if (!advancedMode) {
			return true
		}
		outer@ for ((flag, fulfilled) in flagsState) {
			val value = flags[flag]
			if (fulfilled && value == Search.EXCLUDE) {
				return false
			}
			if (!fulfilled && value == Search.INCLUDE) {
				for ((checkFlag, checkFulfilled) in flagsState) {
					if (flag == checkFlag) {
						continue
					}
					val checkValue = flags[checkFlag]
					if (checkFulfilled && checkValue == Search.INCLUDE) {
						continue@outer
					}
				}
				return false
			}
		}
		return true
	}

	fun hasIncluded(): Boolean = resultInclude.isNotEmpty()

	fun getIncluded(): Iterable<String> = resultInclude

	fun getExcluded(): Iterable<String> = resultExclude

	companion object {
		private val PATTERN_LONG_QUERY_PART = Pattern.compile("(?:^| )(-)?\"(.*?)\"(?= |$)")
	}
}
