#!/usr/bin/env python3
"""Read-only browser checks against a disposable Observer test deployment.

Requires Playwright/Chromium on the development PC. Credentials are read from a
private env file, never passed on the command line or printed.
"""
import argparse
import json
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument('--url', required=True)
parser.add_argument('--env-file', type=Path, required=True)
parser.add_argument('--job', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
env = dict(line.split('=', 1) for line in args.env_file.read_text().splitlines() if '=' in line and not line.startswith('#'))
args.output.mkdir(parents=True, exist_ok=True)
checks, errors, external = {}, [], []
url = args.url.rstrip('/')
with sync_playwright() as pw:
    browser = pw.chromium.launch(headless=True, args=['--no-sandbox'])
    page = browser.new_page(viewport={'width': 1600, 'height': 1100})
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.on('request', lambda r: external.append(r.url) if not r.url.startswith(url + '/') else None)
    page.goto(url + '/login.html')
    page.locator('[name=username]').fill(env.get('OBSERVER_ADMIN_USER', 'admin'))
    page.locator('[name=password]').fill(env['OBSERVER_ADMIN_PASSWORD'])
    page.locator('button[type=submit]').click()
    page.wait_for_url(url + '/')
    page.goto(url + '/#catalog')
    page.locator('.catalog-pager').first.wait_for()
    first = page.locator('tbody tr').first.inner_text()
    page.locator('.catalog-pager').first.get_by_role('button', name='다음', exact=True).click()
    page.wait_for_function("() => document.querySelector('.catalog-pager').textContent.includes('2 /')")
    assert page.locator('tbody tr').first.inner_text() != first
    page.locator('.catalog-pager').first.get_by_role('button', name='이전', exact=True).click()
    page.wait_for_function("() => document.querySelector('.catalog-pager').textContent.includes('1 /')")
    assert page.locator('tbody tr').first.inner_text() == first
    last_page = int(page.locator('.catalog-pager').first.get_by_role('button', name='마지막', exact=True).get_attribute('data-page')) + 1
    page.locator('.catalog-pager').first.get_by_role('button', name='마지막', exact=True).click()
    page.wait_for_function("n => document.querySelector('.catalog-pager .small').textContent.startsWith(n + ' / ' + n)", arg=last_page)
    assert page.locator('.catalog-pager').first.get_by_role('button', name='다음', exact=True).is_disabled()
    assert 0 < page.locator('tbody tr').count() <= 50
    page.locator('.catalog-pager').first.get_by_role('button', name='처음', exact=True).click()
    page.wait_for_function("() => document.querySelector('.catalog-pager .small').textContent.startsWith('1 /')")
    checks['firstPreviousNextLast'] = True

    # Hold page two while a search overtakes it; the delayed page must be ignored.
    pending = []
    def delayed(route):
        query = parse_qs(urlparse(route.request.url).query)
        if query.get('page') == ['1'] and not query.get('query'):
            pending.append((route, route.fetch()))
        else:
            route.continue_()
    page.route('**/api/catalog/page?*', delayed)
    page.locator('.catalog-pager').first.get_by_role('button', name='다음', exact=True).click()
    for _ in range(100):
        if pending:
            break
        page.wait_for_timeout(20)
    assert pending
    page.locator('#catalog-search input').fill('CVE-2021-44228')
    page.locator('#catalog-search button').click()
    page.wait_for_function("() => document.querySelector('.catalog-pager').textContent.includes('1 / 1 페이지')")
    searched = page.locator('tbody').inner_text()
    route, response = pending.pop()
    route.fulfill(response=response)
    page.wait_for_timeout(250)
    assert page.locator('tbody').inner_text() == searched
    assert page.locator('#catalog-search input').input_value() == 'CVE-2021-44228'
    page.unroute('**/api/catalog/page?*', delayed)
    checks['stalePageCannotOverwriteSearch'] = True

    page.locator('#catalog-search input').fill('')
    page.locator('#catalog-search button').click()
    page.wait_for_function("() => document.querySelectorAll('tbody tr').length === 50")
    def failure(route):
        route.fulfill(status=500, json={'message': '페이지 재시도 회귀 검사'})
    page.route('**/api/catalog/page?*', failure)
    page.locator('.catalog-pager').first.get_by_role('button', name='다음', exact=True).click()
    page.wait_for_function("() => document.querySelector('#toast').textContent.includes('재시도')")
    assert page.locator('tbody tr').first.inner_text() == first
    page.unroute('**/api/catalog/page?*', failure)
    page.locator('.catalog-pager').first.get_by_role('button', name='다음', exact=True).click()
    page.wait_for_function("() => document.querySelector('.catalog-pager').textContent.includes('2 /')")
    checks['failureRetryDoesNotSkipPage'] = True
    page.wait_for_timeout(6600)
    page.screenshot(path=str(args.output / 'catalog-after.png'), full_page=True)

    page.goto(url + '/#jobs')
    page.locator('[data-action=detail][data-id="' + args.job + '"]').click()
    page.locator('#modal[open]').wait_for()
    assert 'CVE-2021-44228' in page.locator('#modal-content').inner_text()
    assert '적용 플랫폼 미일치/미확인' not in page.locator('#modal-content').inner_text()
    assert page.locator('.report-section').count() == 3
    assert not page.locator('.report-section[open]').count()
    page.screenshot(path=str(args.output / 'report-desktop.png'), full_page=True)
    assert 'JetBrains' not in page.evaluate('getComputedStyle(document.documentElement).fontFamily')
    checks['compactActionsAndCollapsedEvidence'] = True
    for name, width, height, zoom in [('mobile', 390, 844, 1), ('zoom', 1440, 1000, 2)]:
        page.set_viewport_size({'width': width, 'height': height})
        page.evaluate('(zoom) => document.documentElement.style.zoom = zoom', zoom)
        page.screenshot(path=str(args.output / ('report-' + name + '.png')), full_page=True)
        assert page.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1')
    checks['mobileAnd200PercentZoom'] = True
    assert not errors, errors
    assert not external, external
    checks['noScriptErrorsOrExternalAssets'] = True
    browser.close()
(args.output / 'ui-verification.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2))
print(json.dumps(checks, ensure_ascii=False))
