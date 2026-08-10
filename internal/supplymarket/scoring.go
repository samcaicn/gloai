package supplymarket

import (
	"fmt"
	"sort"
	"strings"
)

// computeScore scores an item on a 0..100 scale (pure rules, no LLM):
//
//	field completeness  40
//	description quality 30
//	price reasonableness 10
//	clarification value 20
func computeScore(it *Item) float64 {
	return clamp(0, 100,
		fieldCompleteness(it)+qualityScore(it)+priceScore(it)+clarificationValue(it))
}

func clamp(lo, hi, v float64) float64 {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

// fieldCompleteness checks the 7 required fields; each contributes 40/7 pts.
func fieldCompleteness(it *Item) float64 {
	titleOK := len(strings.TrimSpace(it.Title)) >= 2
	descOK := len(strings.TrimSpace(it.Description)) >= 10
	categoryOK := strings.TrimSpace(it.Category) != ""
	priceOK := it.Price > 0
	currencyOK := CurrencyWhitelist[strings.ToUpper(strings.TrimSpace(it.Currency))]
	locationOK := strings.TrimSpace(it.Location) != ""
	contactOK := len(strings.TrimSpace(it.Contact)) >= 3

	filled := 0
	for _, ok := range []bool{titleOK, descOK, categoryOK, priceOK, currencyOK, locationOK, contactOK} {
		if ok {
			filled++
		}
	}
	return round2(float64(filled) * 40.0 / 7.0)
}

// qualityScore rewards a meaty description, category-relevant keywords and a
// non-trivial title. Cap at 30.
func qualityScore(it *Item) float64 {
	text := strings.ToLower(it.Title + " " + it.Description)
	descLen := len(strings.TrimSpace(it.Description))

	var s float64
	switch {
	case descLen >= 120:
		s += 22
	case descLen >= 80:
		s += 20
	case descLen >= 50:
		s += 18
	case descLen >= 35:
		s += 16
	case descLen >= 20:
		s += 12
	}
	if kws := CategoryKeywords[strings.TrimSpace(it.Category)]; len(kws) > 0 {
		for _, kw := range kws {
			if strings.Contains(text, strings.ToLower(kw)) {
				s += 5
				break
			}
		}
	}
	if len(strings.TrimSpace(it.Title)) >= 6 {
		s += 6
	}
	return round2(min(s, 30))
}

// priceScore gives 10 pts for a positive price denominated in a whitelisted
// currency, else 3.
func priceScore(it *Item) float64 {
	if it.Price <= 0 {
		return 0
	}
	if CurrencyWhitelist[strings.ToUpper(strings.TrimSpace(it.Currency))] {
		return 10
	}
	return 3
}

// clarificationValue scores the quality of answers given in prior rounds (0..20).
func clarificationValue(it *Item) float64 {
	if len(it.ClarificationRounds) == 0 {
		return 0
	}
	total, good := 0, 0
	for _, r := range it.ClarificationRounds {
		byQID := map[string]string{}
		for _, a := range r.Answers {
			byQID[a.QID] = a.Text
		}
		for _, q := range r.Questions {
			total++
			if len(strings.TrimSpace(byQID[q.QID])) >= 4 {
				good++
			}
		}
	}
	if total == 0 {
		return 0
	}
	ratio := float64(good) / float64(total)
	bonus := min(8.0, float64(len(it.ClarificationRounds))*3.0)
	return round2(min(ratio*12.0+bonus, 20))
}

// generateClarificationQuestions builds up to 3 questions about missing fields.
func generateClarificationQuestions(it *Item) []ClarificationQuestion {
	var qs []struct {
		field, text string
	}
	category := strings.TrimSpace(it.Category)
	if category == "" || !validCategory(category) {
		qs = append(qs, struct{ field, text string }{
			"category",
			fmt.Sprintf("请补充%s的品类(可选: 服务/商品/场地/设备/知识;或你自定义的品类)。", it.ItemType),
		})
	}
	if it.Price <= 0 {
		qs = append(qs, struct{ field, text string }{"price", fmt.Sprintf("请补充%s的价格(正数)。", it.ItemType)})
	}
	if !CurrencyWhitelist[strings.ToUpper(strings.TrimSpace(it.Currency))] {
		qs = append(qs, struct{ field, text string }{
			"currency",
			"请补充价格币种(可选: CNY/USD/EUR/GBP/JPY/HKD/TWD)。",
		})
	}
	if strings.TrimSpace(it.Location) == "" {
		qs = append(qs, struct{ field, text string }{"location", "请补充所在城市/区域。"})
	}
	if len(strings.TrimSpace(it.Contact)) < 3 {
		qs = append(qs, struct{ field, text string }{"contact", "请补充联系方式(邮箱/微信/电话之一)。"})
	}
	if len(strings.TrimSpace(it.Description)) < DescriptionMinLen {
		qs = append(qs, struct{ field, text string }{
			"description",
			fmt.Sprintf("请进一步描述%s的详细信息(至少 %d 字)。", it.ItemType, DescriptionMinLen),
		})
	}
	if len(strings.TrimSpace(it.Title)) < 2 {
		qs = append(qs, struct{ field, text string }{"title", fmt.Sprintf("请补充%s的标题(一句话概括)。", it.ItemType)})
	}
	if len(qs) == 0 {
		return nil
	}
	limit := min(len(qs), 3)
	out := make([]ClarificationQuestion, 0, limit)
	for i := 0; i < limit; i++ {
		out = append(out, ClarificationQuestion{
			QID:  fmt.Sprintf("q_%s_%s_%d", it.ItemID, qs[i].field, i),
			Text: qs[i].text,
		})
	}
	return out
}

// validCategory reports whether the category is one of the known ones.
func validCategory(c string) bool {
	_, ok := CategoryKeywords[c]
	return ok
}

// applyAnswersToItem writes clarifying answers back onto the item fields.
func applyAnswersToItem(it *Item, answers []ClarificationAnswer) {
	fields := map[string]string{}
	for _, a := range answers {
		text := strings.TrimSpace(a.Text)
		if text == "" {
			continue
		}
		parts := strings.Split(a.QID, "_")
		if len(parts) >= 3 {
			fields[parts[2]] = text
		}
	}
	if v, ok := fields["title"]; ok {
		it.Title = v
	}
	if v, ok := fields["description"]; ok {
		if strings.TrimSpace(it.Description) == "" {
			it.Description = v
		} else if !strings.Contains(it.Description, v) {
			it.Description = it.Description + "\n" + v
		}
	}
	if v, ok := fields["category"]; ok {
		it.Category = v
	}
	if v, ok := fields["price"]; ok {
		if f, err := parseFloatSafe(v); err == nil {
			it.Price = f
		}
	}
	if v, ok := fields["currency"]; ok {
		it.Currency = v
	}
	if v, ok := fields["location"]; ok {
		it.Location = v
	}
	if v, ok := fields["contact"]; ok {
		it.Contact = v
	}
}

// refineDescription assembles a readable summary from title + fields +
// clarifying answers, mirroring the upstream rule-based synthesis.
func refineDescription(it *Item) string {
	var header []string
	if t := strings.TrimSpace(it.Title); t != "" {
		header = append(header, t)
	}
	if c := strings.TrimSpace(it.Category); c != "" {
		header = append(header, "【"+c+"】")
	}

	var body []string
	if d := strings.TrimSpace(it.Description); d != "" {
		body = append(body, d)
	}
	baseDesc := strings.TrimSpace(it.Description)
	for _, r := range it.ClarificationRounds {
		for _, a := range r.Answers {
			text := strings.TrimSpace(a.Text)
			if text == "" {
				continue
			}
			parts := strings.Split(a.QID, "_")
			field := ""
			if len(parts) >= 3 {
				field = parts[2]
			}
			if field != "description" {
				continue
			}
			if baseDesc != "" && strings.Contains(baseDesc, text) {
				continue
			}
			dup := false
			for _, b := range body {
				if b == text {
					dup = true
					break
				}
			}
			if !dup {
				body = append(body, text)
			}
		}
	}

	var tail []string
	if it.Price > 0 {
		cur := strings.ToUpper(strings.TrimSpace(it.Currency))
		if cur == "" {
			cur = "CNY"
		}
		tail = append(tail, fmt.Sprintf("价格：%s %v", cur, trimFloat(it.Price)))
	}
	if l := strings.TrimSpace(it.Location); l != "" {
		tail = append(tail, "位置："+l)
	}
	if c := strings.TrimSpace(it.Contact); c != "" {
		tail = append(tail, "联系："+c)
	}

	var lines []string
	if len(header) > 0 {
		lines = append(lines, strings.Join(header, " "))
	}
	if len(body) > 0 {
		lines = append(lines, strings.Join(body, "\n"))
	}
	if len(tail) > 0 {
		lines = append(lines, strings.Join(tail, "  "))
	}
	refined := strings.TrimSpace(strings.Join(lines, "\n"))
	if refined == "" {
		refined = it.Title
	}
	if refined == "" {
		refined = fmt.Sprintf("%s %s", it.ItemType, it.ItemID)
	}
	return refined
}

// sortedCategories returns the known category names for the UI.
func sortedCategories() []string {
	names := make([]string, 0, len(CategoryKeywords))
	for c := range CategoryKeywords {
		names = append(names, c)
	}
	sort.Strings(names)
	return names
}

// Categories returns the known category names (exported for the UI).
func Categories() []string { return sortedCategories() }
