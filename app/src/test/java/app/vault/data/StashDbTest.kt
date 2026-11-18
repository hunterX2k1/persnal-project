package app.vault.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StashDbTest {
    @Test
    fun parsesPerformerAndMerges() {
        val json = JSONObject("""{
          "id":"abc-123","name":"Jane Example","aliases":["Janey"],"gender":"FEMALE","birth_date":"1990-05-04",
          "ethnicity":"MIDDLE_EASTERN","country":"US","eye_color":"BROWN","hair_color":"AUBURN","height":168,
          "cup_size":"dd","band_size":34,"waist_size":25,"hip_size":36,"breast_type":"NATURAL",
          "career_start_year":2012,"career_end_year":null,"scene_count":120,
          "tattoos":[{"location":"Left wrist","description":"Star"}],"piercings":[],
          "images":[{"url":"https://img/small.jpg","width":300},{"url":"https://img/big.jpg","width":1200}],
          "urls":[{"url":"https://www.babepedia.com/babe/Jane_Example","site":{"name":"Babepedia"}},{"url":"https://x.com/jane","site":{"name":"Twitter"}}]
        }""")
        val p = StashDbApi { "" }.parse(json)!!
        assertEquals("sdb:abc-123", p.id)
        assertEquals("Female", p.gender); assertEquals("Middle Eastern", p.ethnicity); assertEquals("United States", p.nationality)
        assertEquals("DD", p.cup); assertEquals(34, p.bust); assertEquals(1, p.natural); assertEquals("Taurus", p.zodiac)
        assertEquals("https://img/big.jpg", p.image); assertEquals("Left wrist: Star", p.tattoos); assertNull(p.piercings)
        assertEquals(listOf("Babepedia" to "https://www.babepedia.com/babe/Jane_Example", "Twitter" to "https://x.com/jane"), p.linkList())

        val tpdb = Performer(id = "tp1", name = "Jane Example", heightCm = 170, image = "https://tp/img.jpg")
        val merged = tpdb.fillFrom(p)
        assertEquals("tp1", merged.id); assertEquals(170, merged.heightCm) // own data wins
        assertEquals("DD", merged.cup); assertEquals(2, merged.linkList().size) // gaps and links filled in
    }
}
