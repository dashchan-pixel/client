package com.mishiranu.dashchan.text

import android.text.Spanned

class SpanComparator(private val spanned: Spanned, private val property: Property) : Comparator<Any> {
	enum class Property(internal val extract: Extract) {
		START(Extract { spanned, span -> spanned.getSpanStart(span) }),
		END(Extract { spanned, span -> spanned.getSpanEnd(span) });

		internal fun interface Extract {
			fun get(spanned: Spanned, span: Any): Int
		}
	}

	override fun compare(o1: Any, o2: Any): Int {
		val i1 = property.extract.get(spanned, o1)
		val i2 = property.extract.get(spanned, o2)
		return i1.compareTo(i2)
	}
}
