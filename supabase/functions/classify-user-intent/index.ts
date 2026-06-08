import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const MOONSHOT_API_URL = "https://api.moonshot.cn/v1/chat/completions";

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }

  if (req.method !== 'POST') {
    return new Response(JSON.stringify({ error: 'Method Not Allowed' }), {
      status: 405,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    });
  }

  try {
    const moonshotApiKey = Deno.env.get("MOONSHOT_API_KEY");
    if (!moonshotApiKey) {
      return new Response(JSON.stringify({ error: 'Internal Server Error (Missing MOONSHOT_API_KEY)' }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    const body = await req.json();
    const { userText, date, todayRecordSummary, recentMessages } = body;

    if (!userText) {
      return new Response(JSON.stringify({ error: 'Missing userText' }), {
        status: 400,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    const systemPrompt = `你是 DayZero（一款减脂陪伴 App）的自然语言语义分类器。
你的唯一任务是把用户的自然语言输入拆解成结构化的 JSON，帮助 App 决定后续的业务链路。
绝不生成面对用户的回复文本。不要写数据。

核心字段定义：
1. \`primaryIntent\` (String): 主意图。
   - \`food_logging\`: 用户陈述自己吃过/将要吃的食物（客观记录）。
   - \`food_info_query\`: 用户询问某种食物的营养、热量、能不能吃。
   - \`craving_support\`: 用户表达想吃/馋某种食物，但还没吃。
   - \`emotional_food_logging\`: 用户因为情绪崩溃/压力大而暴食，并表达了负面情绪（必须是真的吃下去了，且有明显情绪词如“忍不住”“崩溃”“罪恶”）。
   - \`weight_logging\`: 记录体重。
   - \`daily_advice\` / \`daily_summary\`: 询问今天整体吃得如何。
   - \`food_edit\` / \`food_delete\`: 修改或删除记录。
   - \`general_chat\`: 打招呼、闲聊、或不符合上述分类。
   - \`unsupported\`: 无法识别。

2. \`speechAct\` (String): 言语行为。
   - \`logging\`: 陈述句（“我吃了苹果”）。
   - \`question\`: 疑问句（“苹果热量多高”）。
   - \`command\`: 祈使句（“帮我删掉苹果”）。
   - \`expression\`: 情绪表达（“我好想吃苹果啊”、“完了我又吃多了”）。
   - \`unknown\`: 无法归类。

3. \`consumptionStatus\` (String): 进食状态（非常关键！）。
   - \`consumed\`: 已经吃进肚子里了（“刚才吃了”、“昨晚吃了”）。
   - \`planning\`: 计划要吃，准备要吃（“中午打算吃”）。
   - \`craving\`: 纯粹是馋，还没吃（“好想吃炸鸡”）。
   - \`unknown\`: 没提食物，或者状态不明。

4. \`shouldCreateDraft\` (Boolean): 是否允许 App 创建记录草稿。
   - **严格规则**：仅当 \`consumptionStatus\` 为 \`consumed\` 且 \`primaryIntent\` 为 \`food_logging\` 或 \`emotional_food_logging\` 时，才输出 true。其他情况（包括 \`craving\` 和 \`planning\`）必须输出 false。

5. \`shouldAskMealTime\` (Boolean):
   - 如果 \`shouldCreateDraft = true\`，判断用户是否在句子里明确说明了这顿饭是“哪一餐”（早/午/晚/加餐/夜宵）。如果没说明，输出 true（App 会去问）。如果说明了，输出 false。如果 \`shouldCreateDraft = false\`，直接输出 false。

6. \`shouldWriteData\` (Boolean):
   - 永远输出 false。写数据库必须由用户在 UI 确认，AI 不允许直接跳过草稿写入。

7. \`containsFood\` (Boolean): 句子中是否提到了具体的食物。
8. \`foodText\` (String | null): 提取出食物及分量部分，如果没提食物则为 null。
9. \`containsEmotion\` (Boolean): 句子中是否有强烈的正负面情绪。
10. \`mealTimeMentioned\` (Boolean): 是否明确提及了餐次或具体时间段（如中午、早上）。

请严格只输出符合以下格式的 JSON 对象，不包含任何 markdown 代码块标记：
{
  "primaryIntent": "...",
  "speechAct": "...",
  "consumptionStatus": "...",
  "shouldCreateDraft": true/false,
  "shouldAskMealTime": true/false,
  "shouldWriteData": false,
  "containsFood": true/false,
  "foodText": "...",
  "containsEmotion": true/false,
  "mealTimeMentioned": true/false,
  "weightMentioned": true/false,
  "shouldComfortFirst": true/false,
  "confidence": 0.9,
  "reason": "简短说明为什么这么分类"
}`;

    const recentContext = (recentMessages || []).map((m: any) => `${m.role}: ${m.text}`).join('\n');
    const userContent = `【当前日期】${date || ''}\n【今日记录摘要】${todayRecordSummary || ''}\n【近期聊天记录】\n${recentContext}\n\n【用户最新输入】\n${userText}`;

    const response = await fetch(MOONSHOT_API_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${moonshotApiKey}`,
      },
      body: JSON.stringify({
        model: "moonshot-v1-8k",
        messages: [
          { role: "system", content: systemPrompt },
          { role: "user", content: userContent }
        ],
        response_format: { type: "json_object" },
        max_tokens: 1000
      }),
    });

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({ message: "Unknown error" }));
      return new Response(JSON.stringify({ 
        error: 'Kimi API Request Failed', 
        status: response.status, 
        detail: errorData.error?.message || errorData.message 
      }), {
        status: 502,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    const kimiResult = await response.json();
    const contentStr = kimiResult.choices[0].message.content;
    
    let parsedContent;
    try {
      parsedContent = JSON.parse(contentStr);
    } catch (e) {
      return new Response(JSON.stringify({ error: 'Failed to parse AI JSON', raw: contentStr }), {
        status: 502,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    return new Response(JSON.stringify(parsedContent), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    });

  } catch (error: any) {
    return new Response(JSON.stringify({ error: 'Internal Server Error', detail: error.message }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    });
  }
});
