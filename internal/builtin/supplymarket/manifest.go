package supplymarket

import (
	"encoding/json"

	"github.com/ceoadmin/CEOadmin/internal/builtin"
)

func init() {
	builtin.Register(builtin.AppManifest{
		Slug:        "supply-market",
		Name:        "供采市场",
		Description: "供应与采购撮合平台：发布供应/采购信息，自动评分审核，跨用户公开市场浏览，双向智能撮合，买家卖家在线沟通。",
		Icon:        "🛒",
		Readme: `供采市场 是一个供应与采购撮合子应用。

核心能力：
- **发布供应 / 采购**：填写标题、描述、品类、价格、币种、地区、联系方式，系统按 100 分制规则自动评分，≥40 分自动上架；不足则自动追问最多 3 轮，仍不达标自动拒绝。
- **公开市场**：所有已上架（VERIFIED）的供应与采购对全平台用户公开可见，支持按类型 / 品类 / 地区 / 价格筛选，按质量分排序。
- **智能撮合**：对任意一条供应，自动匹配最合适的采购（反之亦然），按品类一致 + 关键词重叠 + 价格接近度综合打分。
- **在线沟通**：买家可直接向卖家发起会话，双方（发布者与咨询者）均可收发消息、查看完整历史。

所有数据存储在 Hub 系统配置库（SQLite / PostgreSQL 通用），无需额外部署。`,
		Guide: `## 使用供采市场

1. 左侧导航进入「供采市场」。
2. 「发布」页签：选择类型（供应 / 采购），填写信息提交。若评分未达标，系统会给出追问问题，按提示补充后重新提交。
3. 「市场」页签：浏览所有已上架的供应与采购，筛选并查看详情。
4. 「撮合」页签：输入一条我的供需项，查看系统推荐的匹配对方。
5. 「会话」页签：管理我的咨询会话，与对方在线沟通。`,
		Homepage:     "/dashboard/supply-market",
		Scopes:       []string{},
		Events:       []string{},
		ConfigSchema: json.RawMessage(`{}`),
	}, nil) // no event handler — the app is driven by the Web UI
}
