---
name: amazon
display: Amazon
packages: ["com.amazon.mShop.android.shopping"]
aliases: ["Amazon", "亚马逊", "amazon"]
sites: ["amazon.com"]
open: Amazon
description: 全球电商购物:商品搜索、比价、购物车与订单跟踪
---
## 是什么
Amazon 是全球跨境电商购物 App,包名 `com.amazon.mShop.android.shopping`。网站知识见 `amazon.com`。支持在不同国家站点搜索商品、下单结算与追踪物流。

## 界面
底部导航包含四个标签:「首页」「我的账号 (You)」「购物车 (Cart)」「菜单 (三条横线)」。
- **首页**:顶部为全宽搜索栏,带相机扫描(识别条码或实物)与麦克风图标;搜索栏下方常驻配送地址胶囊「配送至 (Deliver to)...」;主体陈列促销秒杀、基于浏览历史的推荐与分类导购。
- **搜索结果页**:顶部有筛选(Filters:品牌、Prime 会员专享、价格区间、评分、卖家资质)与排序(Featured, Price low to high, Avg. customer review);商品流中混杂带有「Sponsored (赞助广告)」标记的推广卡片。
- **商品详情页**:上方为多图轮播,中间为商品全名、星级评价总数、价格与折扣,下方为变体选择区(尺码、颜色、容量)、发货与售卖方(Ships from / Sold by)、黄色「加入购物车 (Add to Cart)」按钮与橙色「立即购买 (Buy Now)」按钮。
- **我的账号页**:顶部常驻「你的订单 (Your Orders)」「再次购买 (Buy Again)」「你的账户 (Your Account)」「你的心愿单 (Your Lists)」。

## 功能
- **搜索与排除广告干扰**:
  - 在顶部搜索栏键入商品全称或型号。在结果列表中优先辨识自然排名商品,注意辨别带有小字「Sponsored」的广告位。
- **加入购物车与安全比价**:
  - 点击商品后,必须先确认选定正确的颜色或尺码变体,核对库存状态(In Stock / 仅剩 X 件)。
  - 执行采购比价任务时,优先点击黄色的「加入购物车 (Add to Cart)」;**切勿轻易点击橙色的「立即购买 (Buy Now)」**,后者可能直接触发一键下单并从默认信用卡扣款。
- **订单追踪**:
  - 进入底部「我的账号」标签,点击「你的订单 (Your Orders)」即可查看近期订单详情与实时物流派送进度(Track Package)。

## 已知现象
- **国家与站点切换影响货架**:Amazon 全球站点独立。若发现货币单位或商品库存异常,需在菜单中检查国家/地区设置(Country & Language),不同国家对应不同收货地址与配送费。
- **一键购买陷阱**:开启 1-Click 买单后,点击「Buy Now」直接完成付款并生成订单,执行自动化任务时应保持高度谨慎。
- **会员优惠弹窗**:结账或浏览时经常弹出 Prime 会员包月或免费试用推荐,需点击「不,谢谢 / No, thanks」拒绝。
