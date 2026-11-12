local APP = "com.haoyisheng.mobile.app"
local ID = "com.haoyisheng.mobile.app:id/"

local function seconds(value)
    if value == nil then return nil end
    local h, m, s = string.match(value, "^(%d+):(%d+):(%d+)$")
    if h ~= nil then return tonumber(h) * 3600 + tonumber(m) * 60 + tonumber(s) end
    m, s = string.match(value, "^(%d+):(%d+)$")
    if m ~= nil then return tonumber(m) * 60 + tonumber(s) end
    return nil
end

local function guard_protected()
    if phone.protected_screen() then
        task.pause("检测到考试、身份核验或积分申领步骤，请手动处理")
    end
end

local function course_window()
    local value = phone.course_list_window()
    if value == nil then
        task.pause("无法读取课程列表位置，已停止自动申请")
    end
    local first, last, total = string.match(value, "^(%d+),(%d+),(%d+)$")
    first, last, total = tonumber(first), tonumber(last), tonumber(total)
    if first == nil or last == nil or total == nil or first > last or last >= total then
        task.pause("课程列表位置数据异常，已停止自动申请")
    end
    return first, last, total
end

local function rewind_course_list()
    for _ = 1, 50 do
        guard_protected()
        if phone.course_list_state() == "unknown" then
            task.pause("课程列表尚未加载完成，请稍后重试")
        end
        local first = course_window()
        if first == 0 then return end
        if not phone.scroll_backward() then
            task.pause("课程列表无法回到顶部，已停止自动申请")
        end
        phone.sleep(500)
        local next_first = course_window()
        if next_first >= first then task.pause("课程列表向上滚动失败，请稍后重试") end
    end
    task.pause("未能定位课程列表顶部，已停止自动申请")
end

local function scan_course_list(resume_learning)
    rewind_course_list()
    local covered_last = -1
    local expected_total = nil
    for _ = 1, 50 do
        guard_protected()
        local first, last, total = course_window()
        if expected_total ~= nil and total ~= expected_total then
            task.pause("课程列表在扫描期间发生变化，请重新启动")
        end
        expected_total = total
        if first > covered_last + 1 or (covered_last >= 0 and last <= covered_last) then
            task.pause("课程列表未能连续扫描，已停止自动申请")
        end
        local page_state = phone.course_list_state()
        if page_state == "unknown" then
            task.pause("课程列表尚未加载完成，请稍后重试")
        end
        if page_state == "incomplete" then
            if resume_learning and phone.click_row_text("学习中") then return true end
            if phone.click_row_text("未学习") then return true end
            task.pause("检测到未完成视频，但无法打开，请手动检查")
        end
        covered_last = last
        if covered_last == total - 1 then return false end
        if not phone.scroll_forward() then task.pause("课程列表向下滚动失败，请稍后重试") end
        phone.sleep(500)
    end
    task.pause("课程视频过多，未能确认全部完成")
end

local function wait_for_player()
    for _ = 1, 20 do
        guard_protected()
        if phone.exists_id(ID .. "tv_video_title1") then return end
        phone.sleep(500)
    end
    task.pause("已点击课程，但播放器没有打开，请稍后重试")
end

local function wait_for_course_detail()
    for _ = 1, 20 do
        guard_protected()
        if phone.exists_id(ID .. "course_name") then return end
        phone.sleep(500)
    end
    task.pause("无法返回课程详情，请手动检查")
end

local function apply_credit()
    guard_protected()
    if not phone.credit_ready() then
        task.pause("尚未确认全部视频完成，已停止申请学分")
    end
    phone.status("课程已学完，正在申请学分")
    if not phone.click_id(ID .. "tv_score_apply") then
        task.pause("申请学分按钮无法点击，请手动检查")
    end
    state.set("credit_apply_clicked", "true")
    for _ = 1, 20 do
        phone.sleep(500)
        guard_protected()
        if phone.exists_text("申请成功") or phone.exists_text("申领成功") or phone.exists_text("已申请") then
            task.finish("学分申请已提交")
        end
    end
    task.pause("已点击申请学分，但未能确认结果，请手动检查")
end

local function ensure_playing()
    local before = seconds(phone.text_id(ID .. "tv_portrait_current_time"))
    if before == nil then
        phone.click_id(ID .. "rl_play_video")
        phone.sleep(500)
        before = seconds(phone.text_id(ID .. "tv_portrait_current_time"))
    end
    phone.sleep(2200)
    local after = seconds(phone.text_id(ID .. "tv_portrait_current_time"))
    if before ~= nil and after ~= nil and after <= before then
        phone.click_id(ID .. "iv_play_pause")
        phone.sleep(2200)
        local resumed = seconds(phone.text_id(ID .. "tv_portrait_current_time"))
        if resumed ~= nil and resumed <= after then
            task.pause("视频未开始播放，请手动检查播放器")
        end
    end
end

local function watch_player()
    local title = phone.text_id(ID .. "tv_video_title1") or "当前课程"
    state.set("last_video", title)
    phone.status("正在学习：" .. title)
    ensure_playing()

    local missing_progress = 0
    local last_progress = nil
    local stalled_cycles = 0
    while phone.exists_id(ID .. "tv_video_title1") do
        guard_protected()
        local current = seconds(phone.text_id(ID .. "tv_portrait_current_time"))
        local total = seconds(phone.text_id(ID .. "tv_portrait_video_time"))
        if current == nil or total == nil then
            missing_progress = missing_progress + 1
            phone.click_id(ID .. "rl_play_video")
            phone.sleep(500)
            current = seconds(phone.text_id(ID .. "tv_portrait_current_time"))
            total = seconds(phone.text_id(ID .. "tv_portrait_video_time"))
            if missing_progress >= 6 and (current == nil or total == nil) then
                task.pause("无法读取视频进度，请手动检查播放器")
            end
        else
            missing_progress = 0
            if last_progress == nil or current > last_progress then
                last_progress = current
                stalled_cycles = 0
            elseif current < total - 2 then
                stalled_cycles = stalled_cycles + 1
                if stalled_cycles == 3 then
                    phone.click_id(ID .. "iv_play_pause")
                elseif stalled_cycles >= 12 then
                    task.pause("视频进度长时间未变化，请手动检查播放器")
                end
            end
        end
        if current ~= nil and total ~= nil and current >= total - 2 then
            phone.status("本节已播放完成，正在查找下一节")
            phone.sleep(3500)
            phone.back()
            wait_for_course_detail()
            phone.click_text("课程列表")
            phone.sleep(800)
            if scan_course_list(false) then
                wait_for_player()
                return
            end
            apply_credit()
        end
        phone.sleep(5000)
    end
end

phone.status("正在打开好医生")
if not phone.launch(APP) then task.pause("未找到好医生 App") end
local opened = false
for _ = 1, 30 do
    if phone.package_name() == APP then
        opened = true
        break
    end
    phone.sleep(500)
end
if not opened then task.pause("好医生启动超时，请手动打开后重试") end

while true do
    if phone.package_name() ~= APP then
        task.pause("已离开好医生，请返回课程页后重新启动")
    end

    guard_protected()
    if phone.exists_id(ID .. "tv_video_title1") then
        watch_player()
    elseif phone.exists_id(ID .. "course_name") then
        phone.status("正在读取课程列表")
        phone.click_text("课程列表")
        phone.sleep(1000)
        if scan_course_list(true) then
            wait_for_player()
        else
            apply_credit()
        end
    elseif phone.exists_id(ID .. "cme_page") then
        phone.status("正在查找继教课程")
        phone.click_id(ID .. "cme_page")
        phone.sleep(1500)
        local opened_course = false
        for _ = 1, 20 do
            guard_protected()
            if phone.exists_id(ID .. "course_name") or phone.exists_id(ID .. "tv_video_title1") then
                opened_course = true
                break
            end
            if phone.exists_id(ID .. "recommend_course_name") then
                phone.status("正在打开推荐课程")
                phone.click_id(ID .. "recommend_course_name")
            end
            phone.sleep(500)
        end
        if not opened_course then task.pause("没有找到可学习的继教课程，请手动检查") end
    else
        task.pause("无法识别当前页面，请返回好医生首页后重试")
    end
end
