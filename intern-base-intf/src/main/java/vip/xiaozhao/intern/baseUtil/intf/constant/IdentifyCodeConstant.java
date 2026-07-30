package vip.xiaozhao.intern.baseUtil.intf.constant;

/**
 * @author allen
 * 保目前逻辑允许Code最大长度为3,确添加新的code时长度不大于3
 */
public class IdentifyCodeConstant {

    public static int CODE_LENGTH = 24;
    static String[] code = {
            "清华", "北大", "上交大", "人大", "北师大", "北邮", "北航", "南大", "南开", "哈工大",
            "浙大", "武大", "复旦", "华科大", "吉大", "中山", "山大", "中科大", "西交大", "东南",
            "南邮", "河海", "北理工", "中农大", "哈佛", "剑桥", "斯坦福"
    };

    public static String getCode(int cur) {
        if (cur >= CODE_LENGTH)
            return code[0];
        return code[cur];
    }

}
